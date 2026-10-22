package com.sonymobile.customizationselector;

import android.content.Context;
import android.os.Build;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.sonymobile.customizationselector.Parser.DynamicConfigParser;
import com.sonymobile.customizationselector.Parser.ModemConfParser;
import com.sonymobile.customizationselector.Parser.ServiceProvidersParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 不相容模式（EFS）要寫進 policy operator_mcc_list 的 MCC。 */
final class OperatorMcc {

    private static final int MCC_LENGTH = 3;
    private static final String MODEM_SUFFIX = "_tar.mbn";

    // 日本型號：docomo SO-01K、au SOV36、SoftBank 701SO；國際版為 G83xx
    private static final Pattern JAPAN_MODEL = Pattern.compile("SO-\\d{2}[A-Z]|SOV\\d{2}|\\d{3}SO");

    private OperatorMcc() {
    }

    /** 是否為日本型號；operator_mcc_list 的改寫目前只套用在日本型號。 */
    static boolean isJapanModel() {
        return JAPAN_MODEL.matcher(Build.MODEL).matches();
    }

    /**
     * 型號的預設 modem（初始 modem、選單的預設項目，以及自動流程找不到對應設定時使用）：
     * 日本型號為各自的原廠 fsg（與原廠 ROM 內 amss_fsg 映像逐位元相同，EFS 模式也從它取 policy），
     * G8342 為雙卡預設、G8341 為單卡預設、G8343 為加拿大版；其他型號回傳 null。
     */
    static String defaultModemFile() {
        switch (Build.MODEL) {
            case "G8342":
                return "amss_fsg_poplar_dsds_tar.mbn";
            case "G8343":
                return "amss_fsg_poplar_canada_tar.mbn";
            case "701SO":
                return "amss_fsg_poplar_701so_softbank_r12c_tar.mbn";
            case "SO-01K":
                return "amss_fsg_poplar_so01k_docomo_r12c_tar.mbn";
            case "SOV36":
                return "amss_fsg_poplar_sov36_kddi_r15c_tar.mbn";
            default:
                return "amss_fsg_poplar_tar.mbn";
        }
    }

    /** 自動流程要寫入 policy 的 MCC：日本型號用 SIM 的 MCC（可能為空字串），其他型號回傳 null 表示不改 policy。 */
    static String autoMcc(Context context) {
        return isJapanModel() ? simMcc(context) : null;
    }

    /** 預設資料卡 SIM 的 MCC，沒有 SIM 回傳空字串。 */
    static String simMcc(Context context) {
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        int subId = CommonUtil.getDefaultSubId(context);
        if (tm == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return "";
        }
        String operator = tm.getSimOperator(subId);
        return TextUtils.isEmpty(operator) || operator.length() < MCC_LENGTH ? "" : operator.substring(0, MCC_LENGTH);
    }

    /** 偵錯模式 MCC 欄位的預設值：選中 mbn 所屬設定的 MCC，沒有對應設定時用 SIM 的 MCC。 */
    static String defaultFor(Context context, String mbnName) {
        String configId = configIdOf(context, mbnName);
        if (configId != null) {
            String mccs = mccsOfConfig(context, configId);
            if (!mccs.isEmpty()) {
                return mccs;
            }
        }
        return simMcc(context);
    }

    /** 找出 modem_conf.json 內變體對應此 mbn 的設定 ID（config id），沒有則回傳 null。 */
    private static String configIdOf(Context context, String mbnName) {
        for (Map.Entry<String, String> e : ModemConfParser.getAll(context).entrySet()) {
            String variant = e.getValue();
            if (!variant.isEmpty() && mbnName.endsWith(variant + MODEM_SUFFIX)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** config id 對應的所有 sim_config_id 的 MCC（去除重複、保留順序），以空白串接。 */
    private static String mccsOfConfig(Context context, String configId) {
        Set<String> simIds = new HashSet<>();
        for (Map.Entry<String, String> e : DynamicConfigParser.getConfiguration(context).entrySet()) {
            if (configId.equals(e.getValue())) {
                simIds.add(e.getKey());
            }
        }
        List<String> mccs = new ArrayList<>();
        for (SimCombination c : ServiceProvidersParser.getServiceProviders(context)) {
            String mcc = c.getMCC();
            if (simIds.contains(c.getSimConfigId()) && !TextUtils.isEmpty(mcc) && !mccs.contains(mcc)) {
                mccs.add(mcc);
            }
        }
        return TextUtils.join(" ", mccs);
    }
}
