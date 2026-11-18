package com.qinghe.ledger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.R
import com.qinghe.ledger.util.MoneyFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reads the widget totals and renders all placed 4x1 widgets.
 *
 * `refresh` is called after every transaction write from the repository, so the
 * numbers update immediately without waiting for the 30-minute periodic update.
 */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, TodayWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        scope.launch { render(appContext, manager, ids) }
    }

    /**
     * Variant for the BroadcastReceiver path: the caller holds a
     * `goAsync()` result and must finish it only after rendering completes.
     */
    fun refreshAsync(context: Context, onDone: () -> Unit) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, TodayWidgetProvider::class.java)
        )
        if (ids.isEmpty()) {
            onDone()
            return
        }
        scope.launch {
            try {
                render(appContext, manager, ids)
            } finally {
                onDone()
            }
        }
    }

    private suspend fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        val app = context.applicationContext as? QingheApp ?: return
        val summary = runCatching {
            app.container.repository.widgetSummary()
        }.getOrNull() ?: return

        val today = MoneyFormat.compactWithSymbol(summary.todayExpenseCents)
        val monthExpense = MoneyFormat.compactWithSymbol(summary.monthExpenseCents)
        val monthIncome = MoneyFormat.compactWithSymbol(summary.monthIncomeCents)

        val views = RemoteViews(context.packageName, R.layout.widget_today)
        views.setTextViewText(R.id.widget_today_expense_value, today)
        views.setTextViewText(R.id.widget_month_expense_value, monthExpense)
        views.setTextViewText(R.id.widget_month_income_value, monthIncome)
        views.setOnClickPendingIntent(R.id.widget_today_root, entryPendingIntent(context))
        views.setContentDescription(
            R.id.widget_today_root,
            "今日支出 $today，本月支出 $monthExpense，本月收入 $monthIncome，点击记一笔"
        )
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun entryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WidgetEntryActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
