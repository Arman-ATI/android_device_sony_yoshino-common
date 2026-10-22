package com.sonymobile.customizationselector;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemProperties;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** modem 狀態檔、狀態通知與自動偵測的偏好設定，供 EventReceiver、小工具與 App 共用。 */
final class StatusInfo {

    static final String KEY_NOTIFICATION = "status_notification";
    static final String KEY_AUTO_DETECT = "auto_detect";
    private static final String CHANNEL_ID = "Sony Modem";
    private static final int NOTIFICATION_ID = 1;
    private static final String MODEM_STATUS = "/data/vendor/modem_switcher_status";
    private static final String MODEM_PREFIX = "amss_fsg_";
    private static final String MODEM_SUFFIX = "_tar.mbn";

    private StatusInfo() {}

    static boolean notificationEnabled(Context context) {
        return EfsSwitcher.prefs(context).getBoolean(KEY_NOTIFICATION, false);
    }

    static void setNotification(Context context, boolean enabled) {
        EfsSwitcher.prefs(context).edit().putBoolean(KEY_NOTIFICATION, enabled).apply();
    }

    static boolean autoDetectEnabled(Context context) {
        return EfsSwitcher.prefs(context).getBoolean(KEY_AUTO_DETECT, false);
    }

    static void setAutoDetect(Context context, boolean enabled) {
        EfsSwitcher.prefs(context).edit().putBoolean(KEY_AUTO_DETECT, enabled).apply();
    }

    /** 讀取狀態檔，回傳 {狀態, 設定}；讀不到時為 "N/A"。 */
    static String[] read() {
        String[] stat = {"N/A", "N/A"};
        try {
            File file = new File(MODEM_STATUS);
            if (file.exists()) {
                String line, data = "";

                BufferedReader br = new BufferedReader(new FileReader(file));
                while ((line = br.readLine()) != null) {
                    data += line;
                }
                data = data.replace("\n", "").replace("\r", "")
                        .replace("{", "").replace("}", "").replace("\"", "").trim();

                return data.equals("") ? stat : new String[]{data.split(",")[0], data.split(",")[1]};
            } else {
                return stat;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return stat;
        }
    }

    static String text(Context context) {
        return text(context, read());
    }

    private static String text(Context context, String[] status) {
        return context.getString(R.string.status_line_status, status[0]) + "\n"
                + context.getString(R.string.status_line_config, displayName(status[1])) + "\n"
                + context.getString(R.string.status_line_cust,
                        SystemProperties.get(Configurator.PROP_TA_AC_VERSION, "N/A"));
    }

    /** 顯示用的設定名稱：去掉 amss_fsg_ 與 _tar.mbn。 */
    static String displayName(String modem) {
        String name = modem;
        if (name.startsWith(MODEM_PREFIX)) {
            name = name.substring(MODEM_PREFIX.length());
        }
        if (name.endsWith(MODEM_SUFFIX)) {
            name = name.substring(0, name.length() - MODEM_SUFFIX.length());
        }
        return name;
    }

    static void notify(Context context) {
        String[] status = read();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        createChannel(context, manager);

        manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.manual_app_name))
                .setContentText(context.getString(R.string.status_line_config, displayName(status[1])))
                .setSmallIcon(R.drawable.ic_baseline_sim_card_24)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(null)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text(context, status)))
                .setColorized(true)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, ManualSwitchActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .addAction(R.drawable.ic_baseline_sim_card_24, context.getString(R.string.status_disable_notification),
                        PendingIntent.getBroadcast(context, 1, new Intent(context, NotificationReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE))
                .build());
    }

    static void cancel(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private static void createChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.status_channel_name), NotificationManager.IMPORTANCE_HIGH);
        channel.setLightColor(Color.WHITE);
        channel.setSound(null, null);
        channel.enableVibration(false);

        manager.createNotificationChannel(channel);
    }
}
