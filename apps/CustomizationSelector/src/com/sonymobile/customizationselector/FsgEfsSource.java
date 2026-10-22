package com.sonymobile.customizationselector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 從選中的 fsg（mbn）取出不相容模式要寫入 modem EFS 的檔案。
 * 明文檔案取自 mbn 內嵌的 tar；tar 內沒有的 IMS 檔案取自 NV 預載 bin（gzip）內的 CNV 記錄。
 * policy 取自日本型號的原廠預設 mbn（見 OperatorMcc.defaultModemFile），把 operator_mcc_list 換成指定的 MCC，
 * svc_mode LIMITED 改為 FULL（見 withOperatorMcc、withFullServiceMode）。
 * 不依賴 Android，可獨立測試。
 */
final class FsgEfsSource {

    /** 不相容模式要改寫的 policy（內容取自手機目前的 EFS）。 */
    static final String POLICY_PATH = "/policyman/carrier_policy.xml";

    /** 由 mbn 取得的 EFS 路徑（依序），連同 POLICY_PATH 共 12 個。 */
    private static final String[] PATHS = {
            "/data/andsf.xml",
            "/data/andsf.xml_Subscription01",
            "/data/ds_andsf_config.txt",
            "/data/ds_andsf_config.txt_Subscription01",
            "/nv/item_files/ims/qp_ims_reg_config_db",
            "/nv/item_files/ims/ims_operation_mode",
            "/Data_Profiles/Profile5",
            "/Data_Profiles/Profile8",
            "/data/ds_dsd_attach_profile.txt_Subscription01",
            "/nv/item_files/modem/nas/nas_ims_setting_mode",
            "/nv/item_files/modem/nas/nas_operator_plmn_list",
    };

    /** fsg tar 內沒有明文檔案的路徑，改由 NV 預載 bin 的 CNV 記錄（單元號、資料大小）取得。 */
    private static final Cnv[] CNV = {
            new Cnv("/nv/item_files/ims/qp_ims_reg_config_db", 5991, 1024),
            new Cnv("/nv/item_files/ims/ims_operation_mode", 4703, 1),
    };

    private static final Pattern MCC = Pattern.compile("\\d{3}");
    private static final Pattern SVC_MODE_LIMITED = Pattern.compile("(<svc_mode>)\\s*LIMITED\\s*(</svc_mode>)");
    private static final Pattern OPERATOR_MCC_LIST =
            Pattern.compile("(<mcc_list name=\"operator_mcc_list\">)[^<]*(</mcc_list>)");

    static final class Entry {
        final String path;
        final byte[] data;

        Entry(String path, byte[] data) {
            this.path = path;
            this.data = data;
        }
    }

    private static final class Cnv {
        final String path;
        final int unit;
        final int size;

        Cnv(String path, int unit, int size) {
            this.path = path;
            this.unit = unit;
            this.size = size;
        }
    }

    private FsgEfsSource() {}

    /** 讀出 fsg（mbn）內嵌的 tar 檔案；路徑是 EFS 絕對路徑。 */
    static List<Entry> readTar(File mbn) throws IOException {
        byte[] img = readAll(mbn);
        int tar = -1;
        for (int i = 257; i + 5 <= Math.min(img.length, 4096); i++) {
            if (img[i] == 'u' && img[i + 1] == 's' && img[i + 2] == 't' && img[i + 3] == 'a' && img[i + 4] == 'r') {
                tar = i - 257;
                break;
            }
        }
        List<Entry> out = new ArrayList<>();
        if (tar < 0) {
            return out;
        }
        int pos = tar;
        while (pos + 512 <= img.length) {
            if (img[pos] == 0 && img[pos + 1] == 0 && img[pos + 2] == 0) {
                break;
            }
            String name = cString(img, pos, 100);
            String prefix = cString(img, pos + 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            long size = parseOctal(img, pos + 124, 12);
            byte type = img[pos + 156];
            pos += 512;
            if ((type == '0' || type == 0) && !name.isEmpty()) {
                if (pos + size > img.length) {
                    break;
                }
                byte[] data = new byte[(int) size];
                System.arraycopy(img, pos, data, 0, (int) size);
                out.add(new Entry(name.startsWith("/") ? name : "/" + name, data));
            }
            pos += (int) ((size + 511) / 512 * 512);
        }
        return out;
    }

    /** 依 PATHS 取出 11 個檔案：tar 有明文的直接用，否則從 LTE NV 預載 bin 取 CNV 記錄。 */
    static List<Entry> sourceFiles(List<Entry> tar) throws IOException {
        List<Entry> out = new ArrayList<>();
        byte[] nv = null;
        for (String path : PATHS) {
            Entry hit = find(tar, path);
            if (hit != null) {
                out.add(hit);
                continue;
            }
            Cnv cnv = cnvFor(path);
            if (cnv == null) {
                if (isOptional(path)) {
                    continue;
                }
                throw new IOException("fsg 內找不到 " + path);
            }
            if (nv == null) {
                nv = lteNvPreload(tar);
            }
            out.add(new Entry(path, cnvRecord(nv, cnv.unit, cnv.size)));
        }
        return out;
    }

    /** 部分業者的 fsg 本來就沒有這兩個 nas 檔（例如遠傳），沒有就不寫。 */
    private static boolean isOptional(String path) {
        return path.equals("/nv/item_files/modem/nas/nas_ims_setting_mode")
                || path.equals("/nv/item_files/modem/nas/nas_operator_plmn_list");
    }

    /** 取出 fsg 內的 carrier_policy.xml。 */
    static byte[] policyOf(List<Entry> tar) throws IOException {
        Entry policy = find(tar, POLICY_PATH);
        if (policy == null) {
            throw new IOException("fsg 內找不到 " + POLICY_PATH);
        }
        return policy.data;
    }

    /**
     * 整理 MCC 清單：以空白或逗號分隔，每個必須是 3 位數字，去除重複。
     * 回傳以單一空白串接的字串；空白或格式不正確回傳 null。
     */
    static String normalizeMccList(String text) {
        if (text == null) {
            return null;
        }
        List<String> mccs = new ArrayList<>();
        for (String token : text.trim().split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!MCC.matcher(token).matches()) {
                return null;
            }
            if (!mccs.contains(token)) {
                mccs.add(token);
            }
        }
        return mccs.isEmpty() ? null : String.join(" ", mccs);
    }

    /**
     * 把 policy 的 operator_mcc_list 內容換成 mcc（已整理過的 MCC 清單），其餘位元組不動。
     * 原檔必須恰好有一個 operator_mcc_list，否則視為失敗。
     */
    static byte[] withOperatorMcc(byte[] policy, String mcc) throws IOException {
        String xml = new String(policy, StandardCharsets.ISO_8859_1);
        Matcher m = OPERATOR_MCC_LIST.matcher(xml);
        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            count++;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + " " + mcc + " " + m.group(2)));
        }
        if (count != 1) {
            throw new IOException("policy 的 operator_mcc_list 出現 " + count + " 次");
        }
        m.appendTail(sb);
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 把 policy 裡所有 svc_mode LIMITED 改成 FULL（日本型號原廠政策初始為 LIMITED，要等在線且有位置才升為 FULL），其餘位元組不動。
     * 沒有 LIMITED 時原樣回傳。
     */
    static byte[] withFullServiceMode(byte[] policy) {
        String xml = new String(policy, StandardCharsets.ISO_8859_1);
        return SVC_MODE_LIMITED.matcher(xml).replaceAll("$1 FULL $2").getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Entry find(List<Entry> entries, String path) {
        for (Entry e : entries) {
            if (e.path.equals(path)) {
                return e;
            }
        }
        return null;
    }

    private static Cnv cnvFor(String path) {
        for (Cnv c : CNV) {
            if (c.path.equals(path)) {
                return c;
            }
        }
        return null;
    }

    /** 取出 LTE 的 NV 預載 bin（排除 cal_true 與 ets）並解壓縮。 */
    private static byte[] lteNvPreload(List<Entry> tar) throws IOException {
        for (Entry e : tar) {
            if (e.path.startsWith("/nvpreload/nv_L") && e.path.endsWith(".bin.gz")
                    && !e.path.contains("_cal_true") && !e.path.contains("_ets")) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(e.data))) {
                    return readStream(in);
                }
            }
        }
        throw new IOException("fsg 內找不到 LTE NV 預載 bin");
    }

    /**
     * 在 NV 預載 bin 中找 CNV 記錄：表頭為 單元號（u16 小端）+ 資料大小（u16 小端），之後接資料。
     * 單元號與大小都必須唯一匹配，否則視為失敗。
     */
    static byte[] cnvRecord(byte[] nv, int unit, int size) throws IOException {
        int found = -1;
        int count = 0;
        for (int i = 0; i + 4 + size <= nv.length; i++) {
            if ((nv[i] & 0xff) == (unit & 0xff) && (nv[i + 1] & 0xff) == (unit >> 8)
                    && (nv[i + 2] & 0xff) == (size & 0xff) && (nv[i + 3] & 0xff) == (size >> 8)) {
                found = i;
                count++;
            }
        }
        if (count != 1) {
            throw new IOException("CNV 單元 " + unit + " 大小 " + size + " 匹配 " + count + " 次");
        }
        byte[] data = new byte[size];
        System.arraycopy(nv, found + 4, data, 0, size);
        return data;
    }

    private static String cString(byte[] b, int off, int len) {
        int n = 0;
        while (n < len && b[off + n] != 0) {
            n++;
        }
        return new String(b, off, n, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= '0' && c <= '7') {
                v = v * 8 + (c - '0');
            } else if (v != 0 || c == 0) {
                break;
            }
        }
        return v;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readStream(in);
        }
    }

    private static byte[] readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }
}
