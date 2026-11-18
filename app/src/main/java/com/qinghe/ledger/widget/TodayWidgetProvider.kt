package com.qinghe.ledger.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class TodayWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                // Hold the broadcast until the Room query + RemoteViews update
                // finish; otherwise the process can be reclaimed mid-render.
                val pending = goAsync()
                WidgetUpdater.refreshAsync(context) { pending.finish() }
            }
            else -> super.onReceive(context, intent)
        }
    }
}
