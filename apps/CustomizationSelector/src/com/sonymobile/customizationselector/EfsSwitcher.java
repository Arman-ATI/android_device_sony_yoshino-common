package com.sonymobile.customizationselector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不相容的 modem 設定（例如單卡 fsg 套到雙卡機）不能整份寫入 fsg，
 * 改為只寫入選中 mbn 取得的 12 個 EFS 檔案（見 FsgEfsSource），日本型號的 policy 取自原廠預設 mbn 並改寫 operator_mcc_list 與 svc_mode
 * （經 /dev/diag 寫進 modem EFS，不更動 fsg、modemst 與 policyman/device_config.xml）。
 */
final class EfsSwitcher {

    private static final String TAG = "EfsSwitcher";
    // 以 EFS 檔案套用過設定後 modem 狀態已與 fsg 不一致，之後選回相容設定要強制重寫 fsg
    static final String KEY_EFS_DIRTY = "manual_efs_dirty";
    static final String APPLIED_FILE = "efs_applied_modem";
    // modem 要隔一段時間才會把寫入的 EFS 落檔，太早重開機會遺失
    private static final int SETTLE_SECONDS = 65;
    private static final String DEVICE_CONFIG = "/policyman/device_config.xml";
    private static final Pattern NUM_SIMS = Pattern.compile("num_sims\\s*=\\s*\"(\\d+)\"");

    interface Progress {
        void update(String message);
    }

    private EfsSwitcher() {}

    /** fsg 的 policyman/device_config.xml 宣告的卡數，無法判斷回傳 -1。 */
    static int modemNumSims(File mbn) {
        try {
            for (FsgEfsSource.Entry e : FsgEfsSource.readTar(mbn)) {
                if (DEVICE_CONFIG.equals(e.path)) {
                    Matcher m = NUM_SIMS.matcher(new String(e.data, StandardCharsets.UTF_8));
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            CSLog.e(TAG, "Unable to read num_sims from " + mbn, e);
        }
        return -1;
    }

    static int deviceNumSims(Context context) {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        return tm == null ? -1 : tm.getSupportedModemCount();
    }

    /**
     * 檔案的卡數不超過本機（雙卡機寫單卡的 fsg 也算相容）才能整份寫入 fsg；
     * 單卡機寫雙卡的 fsg 會讓 modem 對不存在的 Slot2 報錯，所以不相容。
     * 其中一邊無法判斷時視為相容（維持原廠流程）。
     */
    static boolean isCompatible(Context context, File mbn) {
        int modem = modemNumSims(mbn);
        int device = deviceNumSims(context);
        CSLog.d(TAG, "compat check " + mbn.getName() + " num_sims=" + modem + " device=" + device);
        return modem <= 0 || device <= 0 || modem <= device;
    }

    static SharedPreferences prefs(Context context) {
        Context target = CommonUtil.isDirectBootEnabled() ? context.createDeviceProtectedStorageContext() : context;
        return target.getSharedPreferences(Configurator.PREF_PKG, Context.MODE_PRIVATE);
    }

    /** 不相容模式套用的設定檔名稱檔案；相容模式寫入後移除。 */
    static File appliedFile(Context context) {
        return new File(context.getFilesDir(), APPLIED_FILE);
    }

    /** 讀取不相容模式套用的設定檔名稱，沒有或讀不到回傳 null。 */
    static String readApplied(Context context) {
        File file = appliedFile(context);
        if (!file.exists()) {
            return null;
        }
        try {
            return new String(readAll(file), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            CSLog.e(TAG, "Unable to read the applied EFS modem", e);
            return null;
        }
    }

    static void clearApplied(Context context) {
        appliedFile(context).delete();
    }

    /**
     * 顯示進度、寫入 EFS、等 modem 落檔後重開機；失敗則還原並呼叫 onFailed（可為 null）。
     * 寫入成功後記下套用的設定檔並呼叫 onApplied（可為 null），之後才等待落檔。
     */
    static void applyAndReboot(Activity activity, File mbn, String mccList, String rebootReason,
            Runnable onFailed, Runnable onApplied) {
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setTitle(R.string.manual_efs_title)
                .setMessage(R.string.manual_efs_working)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            if (apply(activity, mbn, mccList, msg -> activity.runOnUiThread(() -> dialog.setMessage(msg)))) {
                try {
                    writeFile(appliedFile(activity), mbn.getName().getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    CSLog.e(TAG, "Unable to record the applied EFS modem", e);
                }
                if (onApplied != null) {
                    onApplied.run();
                }
                for (int left = SETTLE_SECONDS; left > 0; left--) {
                    int sec = left;
                    activity.runOnUiThread(() -> dialog.setMessage(activity.getString(R.string.manual_efs_wait, sec)));
                    SystemClock.sleep(1000);
                }
                prefs(activity).edit().putBoolean(KEY_EFS_DIRTY, true).apply();
                new Configurator(activity, null).saveConfigurationKey();
                activity.getSystemService(PowerManager.class).reboot(rebootReason);
            } else {
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(activity, R.string.manual_efs_failed, Toast.LENGTH_LONG).show();
                    if (onFailed != null) {
                        onFailed.run();
                    }
                });
            }
        }, "efs-apply").start();
    }

    /**
     * 備份目前的 EFS（含 policy）→ 寫入選中 mbn 取得的檔案，policy 為日本型號原廠預設 mbn 的 policy，
     * 把 operator_mcc_list 換成 mccList、svc_mode LIMITED 改為 FULL 後的內容 → 失敗則還原。
     * mccList 為空白分隔的 3 位數 MCC；為 null 時（非日本型號）不動 policy，只寫 mbn 取得的檔案。
     * 在背景執行緒呼叫。
     */
    static boolean apply(Context context, File mbn, String mccList, Progress progress) {
        try {
            String mcc = mccList == null ? null : FsgEfsSource.normalizeMccList(mccList);
            if (mccList != null && mcc == null) {
                CSLog.e(TAG, "Invalid operator_mcc_list: '" + mccList + "'");
                return false;
            }
            byte[] policyData = null;
            if (mcc != null) {
                String factory = OperatorMcc.defaultModemFile();
                File factoryMbn = factory == null ? null : new File(ModemSwitcher.MODEM_FS_PATH + factory);
                if (factoryMbn == null || !factoryMbn.exists()) {
                    CSLog.e(TAG, "Factory default modem not found: " + factory);
                    return false;
                }
                policyData = FsgEfsSource.withFullServiceMode(
                        FsgEfsSource.withOperatorMcc(FsgEfsSource.policyOf(FsgEfsSource.readTar(factoryMbn)), mcc));
            }
            List<FsgEfsSource.Entry> entries = FsgEfsSource.sourceFiles(FsgEfsSource.readTar(mbn));
            int policyIndex = entries.size();
            int total = policyIndex + (mcc == null ? 0 : 1);
            File dir = context.getFilesDir();
            File stage = new File(dir, "efs_stage");
            File bak = new File(dir, "efs_bak");
            deleteDir(stage);
            deleteDir(bak);
            stage.mkdirs();
            bak.mkdirs();

            StringBuilder backup = new StringBuilder();
            StringBuilder write = new StringBuilder();
            StringBuilder index = new StringBuilder();
            for (int i = 0; i < policyIndex; i++) {
                FsgEfsSource.Entry e = entries.get(i);
                File local = new File(stage, i + ".bin");
                writeFile(local, e.data);
                backup.append("backup ").append(e.path).append(' ').append(new File(bak, i + ".bin").getAbsolutePath()).append('\n');
                write.append("write ").append(e.path).append(' ').append(local.getAbsolutePath()).append('\n');
                index.append(i).append(' ').append(e.path).append('\n');
            }
            if (mcc != null) {
                backup.append("backup ").append(FsgEfsSource.POLICY_PATH).append(' ')
                        .append(new File(bak, policyIndex + ".bin").getAbsolutePath()).append('\n');
                index.append(policyIndex).append(' ').append(FsgEfsSource.POLICY_PATH).append('\n');
            }
            writeFile(new File(bak, "index.txt"), index.toString().getBytes(StandardCharsets.UTF_8));

            progress.update(context.getString(R.string.efs_progress_backup, total));
            String result = run(context, backup.toString());
            if (result.isEmpty() || result.contains("FAIL")) {
                CSLog.e(TAG, "backup failed: " + result);
                return false;
            }

            if (mcc != null) {
                File policy = new File(stage, policyIndex + ".bin");
                writeFile(policy, policyData);
                write.append("write ").append(FsgEfsSource.POLICY_PATH).append(' ').append(policy.getAbsolutePath()).append('\n');
            }

            progress.update(context.getString(mcc == null ? R.string.efs_progress_write : R.string.efs_progress_write_mcc,
                    mbn.getName(), total, mcc));
            result = run(context, write.toString());
            if (result.isEmpty() || result.contains("FAIL")) {
                CSLog.e(TAG, "write failed: " + result);
                progress.update(context.getString(R.string.efs_progress_restore));
                restore(context, bak);
                return false;
            }
            CSLog.d(TAG, "EFS applied from " + mbn.getName() + ": " + total + " files, operator_mcc_list=" + (mcc == null ? "(unchanged)" : mcc));
            return true;
        } catch (IOException | RuntimeException e) {
            CSLog.e(TAG, "apply failed", e);
            return false;
        } finally {
            deleteDir(new File(context.getFilesDir(), "efs_stage"));
        }
    }

    private static void restore(Context context, File bak) throws IOException {
        StringBuilder cmd = new StringBuilder();
        for (String line : new String(readAll(new File(bak, "index.txt")), StandardCharsets.UTF_8).split("\n")) {
            String[] p = line.trim().split("\\s+", 2);
            if (p.length != 2) {
                continue;
            }
            File bin = new File(bak, p[0] + ".bin");
            if (new File(bak, p[0] + ".bin.absent").exists()) {
                cmd.append("unlink ").append(p[1]).append('\n');
            } else if (bin.exists()) {
                cmd.append("write ").append(p[1]).append(' ').append(bin.getAbsolutePath()).append('\n');
            }
        }
        CSLog.d(TAG, "restore: " + run(context, cmd.toString()).replace('\n', ' '));
    }

    /** 寫命令檔、呼叫 native 批次、回傳結果文字。 */
    private static String run(Context context, String cmd) throws IOException {
        File dir = context.getFilesDir();
        File result = new File(dir, "efs_result.txt");
        result.delete();
        writeFile(new File(dir, "efs_cmd.txt"), cmd.getBytes(StandardCharsets.UTF_8));
        EfsNative.runBatch();
        return result.exists() ? new String(readAll(result), StandardCharsets.UTF_8) : "";
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static void deleteDir(File d) {
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        d.delete();
    }
}
