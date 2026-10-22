package com.sonymobile.customizationselector;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** 桌面小工具：顯示目前的 modem 狀態，點擊後開啟電信切換。 */
public class StatusWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context));
        }
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, StatusWidget.class));
        for (int id : ids) {
            manager.updateAppWidget(id, build(context));
        }
    }

    private static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_status);
        views.setTextViewText(R.id.widget_status_text, StatusInfo.text(context));
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                new Intent(context, ManualSwitchActivity.class), PendingIntent.FLAG_IMMUTABLE));
        return views;
    }
}
