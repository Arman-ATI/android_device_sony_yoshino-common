package com.sonymobile.customizationselector;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;

import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;

public class CustomizationSelectorActivity extends Activity implements OnClickListener {

    private static final String TAG = CustomizationSelectorActivity.class.getSimpleName();

    private AlertDialog mAlertDialog;
    private Configurator mConfigurator;
    private UserPresentReceiver mUserPresentReceiver;

    private void disableActivity() {
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, CustomizationSelectorActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
    }

    private void dismissDialog() {
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
        }
    }

    private void startDialog() {
        if (mAlertDialog == null) {
            Builder builder = new Builder(this, R.style.AppDialog);
            builder.setCancelable(false);
            builder.setMessage(R.string.customization_restart_desc_txt);
            builder.setPositiveButton("OK", this);
            builder.setNegativeButton(android.R.string.cancel, this);
            mAlertDialog = builder.create();
            mAlertDialog.setCanceledOnTouchOutside(false);
            mAlertDialog.setCancelable(false);
            mAlertDialog.getWindow().setType(TYPE_KEYGUARD_DIALOG);
        }
        if (!mAlertDialog.isShowing()) {
            CSLog.d(TAG, "Show dialog");
            mAlertDialog.show();
        }
    }

    public void disableUI() {
        getWindow().addFlags(FLAG_DISMISS_KEYGUARD);
    }

    @Override
    public void onClick(DialogInterface dialogInterface, int i) {
        if (i == DialogInterface.BUTTON_NEGATIVE) {
            // 取消：記下目前狀態，SIM 或版本沒變就不再詢問
            CSLog.d(TAG, "onClick - Cancel");
            disableActivity();
            mConfigurator.saveConfigurationKey();
            dismissDialog();
            finish();
            return;
        }
        CSLog.d(TAG, "onClick - Reboot");
        disableActivity();
        Log.i(getString(R.string.app_name), getString(R.string.customization_restart_desc_txt));
        String modem = mConfigurator.getModem();
        if (!TextUtils.isEmpty(modem) && !EfsSwitcher.isCompatible(this, new File(modem))) {
            // 不相容：不寫 fsg，改套用其 EFS 檔案
            EfsSwitcher.applyAndReboot(this, new File(modem), OperatorMcc.autoMcc(this), getString(R.string.reboot_reason), this::finish, null);
            return;
        }
        EfsSwitcher.clearApplied(this);
        mConfigurator.set();
        mConfigurator.saveConfigurationKey();
        (getSystemService(PowerManager.class)).reboot(getString(R.string.reboot_reason));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        CSLog.d(TAG, "onCreate()");
        mConfigurator = new Configurator(this, CommonUtil.getCarrierBundle(this));

        if (CommonUtil.isSIMLoaded(this, CommonUtil.getDefaultSubId(this)) && mConfigurator.isNewConfigurationNeeded()) {
            disableUI();
            setFinishOnTouchOutside(false);
            setupUserPresent();
            startDialog();
            return;
        }

        disableActivity();
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        CSLog.d(TAG, "onDestroy()");
        dismissDialog();
        if (mUserPresentReceiver != null) {
            unregisterReceiver(mUserPresentReceiver);
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        startDialog();
    }

    private void setupUserPresent() {
        mUserPresentReceiver = new UserPresentReceiver();
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mUserPresentReceiver, intentFilter);
    }
}
