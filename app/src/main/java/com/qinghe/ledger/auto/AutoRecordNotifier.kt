package com.qinghe.ledger.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.qinghe.ledger.MainActivity
import com.qinghe.ledger.R
import com.qinghe.ledger.data.AutoRecordResult
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime

object AutoRecordNotifier {
    private const val CHANNEL_ID = "auto_record_v2"
    private const val NOTIFICATION_ID_BASE = 2100

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动记账提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "微信、支付宝付款被自动记录时提醒"
            setShowBadge(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 150, 100, 150)
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(
        context: Context,
        result: AutoRecordResult,
        source: TransactionSource
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val typeLabel = if (result.type == PaymentType.INCOME) "收入" else "支出"
        val sourceLabel = when (source) {
            TransactionSource.NOTIFICATION -> "通知监听"
            TransactionSource.ACCESSIBILITY -> "页面识别"
            TransactionSource.MANUAL -> "手动"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_auto)
            .setContentTitle("已自动记账 · $typeLabel ¥${MoneyFormat.amount(result.amountCents)}")
            .setContentText("${result.categoryName} · ${result.accountName} · $sourceLabel")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${result.categoryName} · ${result.accountName} · $sourceLabel\n" +
                        "支付时间：${formatDateTime(result.occurredAt)}"
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID_BASE + (result.transactionId % 1000).toInt(),
            notification
        )
    }
}
