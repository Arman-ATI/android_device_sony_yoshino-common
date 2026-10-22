package com.sonymobile.customizationselector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.Window;
import android.widget.PopupMenu;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sonymobile.miscta.MiscTA;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 電信切換（同時是 debug menu 的 modem 切換入口）。
 * 相容（卡數一致）：與自動流程相同，寫 TA 2212（config id）與 TA 2405（modem 指令），
 * 重開機後由 sony-modem-switcher 寫入 fsg 並重設 modemst。
 * 不相容：不動 fsg，改把 fsg 內的 EFS 檔案寫進 modem（EfsSwitcher）。
 */
public class ManualSwitchActivity extends Activity {

    private static final String TAG = "ManualSwitch";
    private static final int TA_CONFIG_ID = 2212;
    private static final String MODEM_SUFFIX = "_tar.mbn";
    private static final String DUAL_DEFAULT = "amss_fsg_poplar_dsds_tar.mbn";
    private static final String SINGLE_DEFAULT = "amss_fsg_poplar_tar.mbn";
    // 偵測選項（右上角選單）與寫入模式、忽略檢查的設定，存在 EfsSwitcher.prefs
    private static final String KEY_DETECT_OPTIONS = "manual_detect_options";
    private static final String KEY_FORCE_MODE = "manual_force_mode";
    private static final String FORCE_AUTO = "auto";
    private static final String FORCE_COMPATIBLE = "compatible";
    private static final String FORCE_INCOMPATIBLE = "incompatible";
    // 清單中被選取項目的底色
    private static final int SELECTED_COLOR = 0x33808080;

    private static final class Carrier {
        final String modemFile;
        // TA 2212 的設定 ID；空字串表示清除，null 表示不更動
        final String configId;
        // 顯示在名稱後面的括號說明（字串資源），0 表示沒有
        final int noteRes;

        Carrier(String modemFile, String configId, int noteRes) {
            this.modemFile = modemFile;
            this.configId = configId;
            this.noteRes = noteRes;
        }

        /** 清單與對話框顯示的名稱：檔名去掉 amss_fsg_ 與 _tar.mbn，後面加括號說明 */
        String label(Context context) {
            String name = modemFile;
            if (name.startsWith("amss_fsg_")) {
                name = name.substring("amss_fsg_".length());
            }
            if (name.endsWith(MODEM_SUFFIX)) {
                name = name.substring(0, name.length() - MODEM_SUFFIX.length());
            } else if (name.endsWith(".mbn")) {
                name = name.substring(0, name.length() - ".mbn".length());
            }
            return noteRes == 0 ? name : name + " (" + context.getString(noteRes) + ")";
        }
    }

    private static final Carrier[] CARRIERS = {
            new Carrier("amss_fsg_poplar_dsds_cht_tw_ims_tar.mbn", "93", R.string.carrier_cht),
            new Carrier("amss_fsg_poplar_dsds_fareastone_tw_ims_tar.mbn", "92", R.string.carrier_fet),
            new Carrier("amss_fsg_poplar_dsds_twm_tw_ims_tar.mbn", "94", R.string.carrier_twm),
            new Carrier("amss_fsg_poplar_dsds_tstar_tw_ims_tar.mbn", "426", R.string.carrier_tstar),
            new Carrier("amss_fsg_poplar_dsds_asiapacific_tw_ims_tar.mbn", "567", R.string.carrier_apt),
    };

    private final List<Carrier> mAll = new ArrayList<>();
    private Carrier mSelected;
    private LinearLayout mList;
    private TextView mCurrent;
    private TextView mMode;
    private LinearLayout mDetect;
    private CheckBox mCheckCompatible;
    private CheckBox mCheckIgnore;
    // 忽略檢查不保存，每次開啟 App 都是不勾選
    private boolean mIgnoreCurrent;
    private EditText mMcc;
    private LinearLayout mMccGroup;
    private String mInitialModem = "";

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        // 不要系統的標題列（自己的版面已有各區塊標題）
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_manual_switch);
        // 內容要避開狀態列與導覽列（邊到邊顯示時會蓋在內容上）
        View root = findViewById(R.id.manual_root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), insets.getSystemWindowInsetTop(),
                    v.getPaddingRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        mCurrent = findViewById(R.id.manual_current);
        mList = findViewById(R.id.manual_list);
        mMode = findViewById(R.id.manual_mode);
        mDetect = findViewById(R.id.manual_detect);
        mCheckCompatible = findViewById(R.id.manual_check_compatible);
        mCheckIgnore = findViewById(R.id.manual_check_ignore);
        mMcc = findViewById(R.id.manual_mcc);
        mMccGroup = findViewById(R.id.manual_mcc_group);

        // 目前 SIM 依自動流程對應的設定放在最前面（沒有 SIM 或找不到對應設定時不出現）
        Carrier simAuto = simAutoCarrier();
        if (simAuto != null) {
            mAll.add(simAuto);
        }
        // 型號的預設；日本型號是原廠 fsg，列在電信業者之前並標為原廠預設。
        // configId 為空字串表示清除 TA 2212，回到預設設定
        String initialFile = initialModemFile();
        boolean factory = OperatorMcc.isJapanModel() && initialFile.equals(OperatorMcc.defaultModemFile());
        Carrier initial = new Carrier(initialFile, "", factory ? R.string.carrier_factory : R.string.carrier_default);
        if (factory) {
            mAll.add(initial);
        }
        mAll.addAll(Arrays.asList(CARRIERS));
        if (!factory) {
            mAll.add(initial);
        }
        String[] files = new File(ModemSwitcher.MODEM_FS_PATH).list();
        if (files != null) {
            Arrays.sort(files);
            for (String f : files) {
                boolean known = false;
                for (Carrier c : mAll) {
                    known |= c.modemFile.equals(f);
                }
                if (!known && f.endsWith(MODEM_SUFFIX)) {
                    mAll.add(new Carrier(f, null, 0));
                }
            }
        }

        // 開啟時選到目前使用的 modem
        String current = "";
        try {
            current = new File(ModemSwitcher.getCurrentModemConfig()).getName();
        } catch (IOException e) {
            CSLog.w(TAG, "Unable to read the current modem");
        }
        mInitialModem = initialModemFile();
        for (Carrier c : mAll) {
            if (c.modemFile.equals(current)) {
                mSelected = c;
                break;
            }
        }
        if (mSelected == null) {
            mSelected = mAll.get(0);
        }
        ((TextView) findViewById(R.id.initial_modem)).setText(StatusInfo.displayName(mInitialModem));

        findViewById(R.id.restore_button).setOnClickListener(v -> {
            if (!TextUtils.isEmpty(mInitialModem)) {
                verifyRestore(mInitialModem);
            }
        });
        ((EditText) findViewById(R.id.search_modem)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                populateList(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        Button button = findViewById(R.id.manual_button);
        button.setOnClickListener(v -> confirm(mSelected));
        // 相容模式勾選框：勾選為相容、取消為不相容（手動指定）
        mCheckCompatible.setOnClickListener(v -> {
            EfsSwitcher.prefs(this).edit().putString(KEY_FORCE_MODE,
                    mCheckCompatible.isChecked() ? FORCE_COMPATIBLE : FORCE_INCOMPATIBLE).apply();
            showMode(mSelected);
        });
        mCheckIgnore.setOnClickListener(v -> {
            mIgnoreCurrent = mCheckIgnore.isChecked();
        });
        findViewById(R.id.manual_menu).setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenuInflater().inflate(R.menu.manual_switch_menu, menu.getMenu());
            menu.getMenu().findItem(R.id.menu_detect_options).setChecked(detectOptions());
            menu.getMenu().findItem(R.id.menu_notification).setChecked(StatusInfo.notificationEnabled(this));
            menu.getMenu().findItem(R.id.menu_auto_detect).setChecked(StatusInfo.autoDetectEnabled(this));
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case R.id.menu_notification:
                        boolean notify = !StatusInfo.notificationEnabled(this);
                        StatusInfo.setNotification(this, notify);
                        if (notify) {
                            StatusInfo.notify(this);
                        } else {
                            StatusInfo.cancel(this);
                        }
                        break;
                    case R.id.menu_auto_detect:
                        StatusInfo.setAutoDetect(this, !StatusInfo.autoDetectEnabled(this));
                        break;
                    default:
                        toggleDetectOptions();
                        break;
                }
                return true;
            });
            menu.show();
        });
        populateList("");
        refreshDetect();
    }

    private void toggleDetectOptions() {
        boolean detect = !detectOptions();
        SharedPreferences.Editor editor = EfsSwitcher.prefs(this).edit().putBoolean(KEY_DETECT_OPTIONS, detect);
        if (!detect) {
            // 關閉偵測選項時回到自動判斷，並清除手動指定
            editor.remove(KEY_FORCE_MODE);
            mIgnoreCurrent = false;
        }
        editor.apply();
        refreshDetect();
    }

    private boolean detectOptions() {
        return EfsSwitcher.prefs(this).getBoolean(KEY_DETECT_OPTIONS, true);
    }

    /** 初始 modem：型號的預設（見 OperatorMcc.defaultModemFile），沒有或檔案不存在時依卡數決定。 */
    private String initialModemFile() {
        String modem = OperatorMcc.defaultModemFile();
        if (modem != null && new File(ModemSwitcher.MODEM_FS_PATH + modem).exists()) {
            return modem;
        }
        return defaultModemFile();
    }

    /** 依搜尋字串重建 modem 清單（比對名稱與括號說明），被選取的項目以底色標示 */
    private void populateList(String query) {
        String q = query.toLowerCase(Locale.getDefault());
        mList.removeAllViews();
        for (Carrier carrier : mAll) {
            String label = carrier.label(this);
            if (!label.toLowerCase(Locale.getDefault()).contains(q)) {
                continue;
            }
            TextView item = (TextView) getLayoutInflater().inflate(R.layout.item_manual_spinner, mList, false);
            item.setText(label);
            item.setBackgroundColor(carrier == mSelected ? SELECTED_COLOR : 0);
            item.setOnClickListener(v -> select(carrier));
            mList.addView(item);
        }
    }

    private void select(Carrier carrier) {
        mSelected = carrier;
        // 選取設定檔時回到該檔的預設寫入模式，清除先前的手動指定
        EfsSwitcher.prefs(this).edit().remove(KEY_FORCE_MODE).apply();
        for (int i = 0; i < mList.getChildCount(); i++) {
            TextView item = (TextView) mList.getChildAt(i);
            item.setBackgroundColor(item.getText().toString().equals(carrier.label(this)) ? SELECTED_COLOR : 0);
        }
        refreshDetect();
    }

    /** 依偵測選項開關顯示勾選框與 MCC 欄位，並同步勾選狀態與寫入模式（依目前選到的設定檔） */
    private void refreshDetect() {
        boolean detect = detectOptions();
        mDetect.setVisibility(detect ? View.VISIBLE : View.GONE);
        if (detect) {
            File mbn = new File(ModemSwitcher.MODEM_FS_PATH + mSelected.modemFile);
            mCheckCompatible.setChecked(mbn.exists() && useCompatibleMode(mbn));
            mCheckIgnore.setChecked(ignoreCurrent());
            // MCC 預設值依選到的設定檔而變，可再手動修改；只在日本型號顯示
            mMccGroup.setVisibility(OperatorMcc.isJapanModel() ? View.VISIBLE : View.GONE);
            mMcc.setText(OperatorMcc.defaultFor(this, mSelected.modemFile));
        }
        showMode(mSelected);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 基底 modem 是 fsg 目前的設定檔（從 modem 狀態取得）
        String base;
        try {
            base = ModemSwitcher.getCurrentModemConfig().replace(ModemSwitcher.MODEM_FS_PATH, "");
        } catch (IOException e) {
            base = "?";
        }
        // 有不相容模式的紀錄就顯示紀錄的設定檔並標註，並另外顯示基底 modem；沒有紀錄時只顯示基底 modem
        String applied = EfsSwitcher.readApplied(this);
        String configId;
        try {
            byte[] id = MiscTA.read(TA_CONFIG_ID);
            configId = id == null ? "" : new String(id, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            configId = "?";
        }
        String text;
        if (applied != null) {
            text = getString(R.string.manual_current_efs, getString(R.string.manual_efs_note),
                    StatusInfo.displayName(applied), StatusInfo.displayName(base), configId);
        } else {
            text = getString(R.string.manual_current, StatusInfo.displayName(base), configId);
        }
        mCurrent.setText(text);
    }

    private String forceMode() {
        return EfsSwitcher.prefs(this).getString(KEY_FORCE_MODE, FORCE_AUTO);
    }

    /** 偵測選項未開啟時一律忽略下列設定 */
    private boolean ignoreCurrent() {
        return detectOptions() && mIgnoreCurrent;
    }

    /** 寫入模式：偵測選項中有手動指定時以指定為準，否則依卡數自動判斷 */
    private boolean useCompatibleMode(File mbn) {
        String force = detectOptions() ? forceMode() : FORCE_AUTO;
        if (FORCE_COMPATIBLE.equals(force)) {
            return true;
        }
        if (FORCE_INCOMPATIBLE.equals(force)) {
            return false;
        }
        return EfsSwitcher.isCompatible(this, mbn);
    }

    /** 顯示目前選到的設定會用哪一種模式寫入 */
    private void showMode(Carrier carrier) {
        File mbn = new File(ModemSwitcher.MODEM_FS_PATH + carrier.modemFile);
        if (!mbn.exists()) {
            mMode.setText(getString(R.string.manual_missing, carrier.modemFile));
            return;
        }
        boolean compatible = useCompatibleMode(mbn);
        String text = getString(compatible ? R.string.manual_mode_fsg : R.string.manual_mode_efs);
        if (detectOptions() && !FORCE_AUTO.equals(forceMode())) {
            text += getString(R.string.manual_mode_forced);
        }
        mMode.setText(text);
    }

    /**
     * 目前 SIM（預設資料卡）依自動流程（sim_config_id → config id → modem.conf）對應的設定；
     * 沒有 SIM、沒有對應設定或對應的 mbn 不存在時回傳 null。
     */
    private Carrier simAutoCarrier() {
        PersistableBundle bundle = CommonUtil.getCarrierBundle(this);
        String variant = bundle.getString(Configurator.KEY_MODEM, "");
        if (TextUtils.isEmpty(variant)) {
            return null;
        }
        String path = new ModemConfiguration(EfsSwitcher.prefs(this)).getModemConfiguration(variant);
        // 找不到對應的 mbn 時 getModemConfiguration 會退回預設，要確認真的是這個 variant
        if (TextUtils.isEmpty(path) || !path.endsWith(variant + MODEM_SUFFIX) || !new File(path).exists()) {
            return null;
        }
        return new Carrier(new File(path).getName(), bundle.getString(Configurator.KEY_CONFIG_ID, ""),
                R.string.carrier_sim_auto);
    }

    /** 依型號的原廠預設：雙卡機用 dsds 的預設，單卡機用單卡的預設。 */
    private String defaultModemFile() {
        if (EfsSwitcher.deviceNumSims(this) == 1 && new File(ModemSwitcher.MODEM_FS_PATH + SINGLE_DEFAULT).exists()) {
            return SINGLE_DEFAULT;
        }
        return DUAL_DEFAULT;
    }

    /** 還原初始 modem 與狀態：與清單中的初始項目相同（清除 TA 2212 設定 ID，再套用初始 modem 後重開機） */
    private void verifyRestore(String modem) {
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle(R.string.debug_verify_title)
                .setMessage(getString(R.string.debug_verify_text, modem))
                .setPositiveButton(android.R.string.ok, (d, i) -> {
                    CSLog.d(TAG, "restore initial modem " + modem);
                    apply(new Carrier(modem, "", R.string.carrier_default));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirm(Carrier carrier) {
        File mbn = new File(ModemSwitcher.MODEM_FS_PATH + carrier.modemFile);
        if (!mbn.exists()) {
            Toast.makeText(this, getString(R.string.manual_missing, carrier.modemFile), Toast.LENGTH_LONG).show();
            return;
        }
        // 卡數一致：原廠流程（寫 TA，開機由 switcher 寫 fsg）；不一致：改寫 EFS 檔案
        boolean compatible = useCompatibleMode(mbn);
        // 只有日本型號的不相容模式才改寫 policy 的 operator_mcc_list（其他型號不改）：
        // 偵測選項開啟時用欄位的值，否則用 SIM 的 MCC
        boolean editMcc = !compatible && OperatorMcc.isJapanModel();
        String mccList = editMcc ? FsgEfsSource.normalizeMccList(
                detectOptions() ? mMcc.getText().toString() : OperatorMcc.simMcc(this)) : null;
        if (editMcc && mccList == null) {
            Toast.makeText(this, detectOptions() ? R.string.manual_mcc_invalid : R.string.manual_mcc_missing,
                    Toast.LENGTH_LONG).show();
            return;
        }
        String message = getString(R.string.manual_confirm, carrier.label(this));
        // 手動指定相容、但檔案卡數與本機不同時，提醒可能無服務
        if (compatible && !EfsSwitcher.isCompatible(this, mbn)) {
            message += "\n" + getString(R.string.manual_force_warning);
        }
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setTitle(R.string.debug_verify_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, i) -> {
                    if (compatible) {
                        apply(carrier);
                    } else {
                        CSLog.d(TAG, "selected " + carrier.modemFile + " (EFS mode)");
                        EfsSwitcher.applyAndReboot(this, mbn, mccList, getString(R.string.reboot_reason_modem_debug), null, () -> {
                            // EFS 寫入成功後才更新設定 ID（TA 2212）
                            if (carrier.configId != null && !writeConfigId(carrier)) {
                                runOnUiThread(() -> Toast.makeText(this, R.string.manual_failed, Toast.LENGTH_LONG).show());
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 寫入設定 ID（TA 2212）：configId 為空字串時清除；寫入失敗回傳 false（可在背景執行緒呼叫）。 */
    private boolean writeConfigId(Carrier carrier) {
        try {
            if (carrier.configId.isEmpty()) {
                Configurator.clearMiscTaConfigId();
            } else {
                MiscTA.write(TA_CONFIG_ID, carrier.configId.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            CSLog.e(TAG, "Failed to write config id", e);
            return false;
        }
    }

    private void apply(Carrier carrier) {
        CSLog.d(TAG, "selected " + carrier.modemFile + " config id=" + carrier.configId);
        boolean idChanged = false;
        if (carrier.configId != null) {
            idChanged = true;
            try {
                byte[] id = MiscTA.read(TA_CONFIG_ID);
                idChanged = !carrier.configId.equals(id == null ? "" : new String(id, StandardCharsets.UTF_8).trim());
            } catch (Exception e) {
                CSLog.w(TAG, "Unable to read config id");
            }
            if (!writeConfigId(carrier)) {
                Toast.makeText(this, R.string.manual_failed, Toast.LENGTH_LONG).show();
                return;
            }
        }
        boolean modemSet;
        if (ignoreCurrent()) {
            // 不比對目前設定，直接寫入 modem 指令（同 ModemConfiguration.setConfiguration 的記錄）
            modemSet = ModemSwitcher.writeModemToMiscTA(carrier.modemFile);
            if (modemSet) {
                EfsSwitcher.prefs(this).edit()
                        .putString(ModemConfiguration.SAVED_MODEM_CONFIG, ModemSwitcher.MODEM_FS_PATH + carrier.modemFile)
                        .apply();
            }
        } else {
            modemSet = new ModemConfiguration(EfsSwitcher.prefs(this))
                    .setConfiguration(ModemSwitcher.MODEM_FS_PATH + carrier.modemFile);
        }
        // EFS 被改寫過時，即使目前 modem 名稱相同也要重寫 fsg
        if (!modemSet && EfsSwitcher.prefs(this).getBoolean(EfsSwitcher.KEY_EFS_DIRTY, false)) {
            modemSet = ModemSwitcher.forceModem(carrier.modemFile);
        }
        if (!modemSet && !idChanged) {
            Toast.makeText(this, R.string.manual_nochange, Toast.LENGTH_LONG).show();
            return;
        }
        if (modemSet) {
            EfsSwitcher.prefs(this).edit().putBoolean(EfsSwitcher.KEY_EFS_DIRTY, false).apply();
            // 相容模式寫入成功，移除不相容模式的紀錄
            EfsSwitcher.clearApplied(this);
        }
        // 避免自動流程在下次開機又依 SIM 切回去
        new Configurator(this, null).saveConfigurationKey();
        getSystemService(PowerManager.class).reboot(getString(R.string.reboot_reason_modem_debug));
    }
}
