package com.mushrea.code.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mushrea.code.R
import com.mushrea.code.device.StopAgentReceiver

class QuickInputWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val ACTION_SEND = "com.mushrea.code.widget.SEND"
        const val ACTION_MIC = "com.mushrea.code.widget.MIC"
        const val EXTRA_TEXT = "widget_text"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_input)

            val sendIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_SEND
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val sendPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    sendIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            val micIntent =
                Intent(context, QuickInputActivity::class.java).apply {
                    action = ACTION_MIC
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            val micPendingIntent =
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 1000,
                    micIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )

            views.setOnClickPendingIntent(R.id.widget_send_button, sendPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_mic_button, micPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_input, sendPendingIntent)

            val stopIntent = Intent(context, StopAgentReceiver::class.java)
            val stopPendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    appWidgetId + 2000,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            views.setOnClickPendingIntent(R.id.widget_stop_button, stopPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
