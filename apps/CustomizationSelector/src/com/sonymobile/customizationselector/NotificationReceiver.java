package com.sonymobile.customizationselector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context != null) {
            StatusInfo.setNotification(context, false);
            StatusInfo.cancel(context);
        }
    }
}
