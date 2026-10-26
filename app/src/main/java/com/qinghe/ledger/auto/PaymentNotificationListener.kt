package com.qinghe.ledger.auto

import android.app.Notification
import android.content.ComponentName
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.model.TransactionSource

/**
 * Reads payment notifications from WeChat and Alipay. The status-bar
 * notification is never dismissed or modified.
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        process(sbn, fromActiveScan = false)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureDiagnostics.setNotificationListenerConnected(true)
        Log.d(TAG, "notification listener connected")
        // Recover a payment notification that arrived before we were bound.
        runCatching {
            activeNotifications?.forEach { sbn ->
                process(sbn, fromActiveScan = true)
            }
        }
    }

    override fun onListenerDisconnected() {
        CaptureDiagnostics.setNotificationListenerConnected(false)
        Log.d(TAG, "notification listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        CaptureDiagnostics.setNotificationListenerConnected(false)
        super.onDestroy()
    }

    private fun process(sbn: StatusBarNotification?, fromActiveScan: Boolean) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in PaymentParser.supportedPackages) return

        val now = System.currentTimeMillis()
        if (fromActiveScan && now - sbn.postTime > ACTIVE_SCAN_WINDOW_MS) return

        val extras = notification.extras ?: Bundle.EMPTY
        val title = firstNonBlank(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        )
        val bodyParts = buildList {
            add(notification.tickerText?.toString())
            add(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString())
            add(extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString())
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.forEach { add(it?.toString()) }
            extractMessagingStyle(extras)?.let { add(it) }
        }.filterNotNull().filter { it.isNotBlank() }
        val body = bodyParts.joinToString(" ")

        val analysis = PaymentParser.analyzeNotification(pkg, title, body)
        CaptureDiagnostics.recordNotification(
            pkg = pkg,
            text = "标题：$title\n内容：$body",
            parsed = analysis.payment != null,
            reason = analysis.reason
        )
        Log.d(TAG, "$pkg ${analysis.reason} title=$title body=${body.take(200)}")

        val parsed = analysis.payment?.copy(
            occurredAtMillis = sbn.postTime.takeIf { it > 0L }
        ) ?: return

        val app = application as? QingheApp ?: return
        app.container.autoRecorder.handle(
            pkg = pkg,
            parsed = parsed,
            source = TransactionSource.NOTIFICATION
        )
    }

    /**
     * WeChat Pay and Alipay often use MessagingStyle. Their visible title is
     * "微信支付" / "支付宝", while the amount lives in the message text.
     */
    private fun extractMessagingStyle(extras: Bundle): String? {
        val messages = mutableListOf<String>()
        @Suppress("DEPRECATION")
        fun read(key: String) {
            extras.getParcelableArray(key)?.forEach { item ->
                val bundle = item as? Bundle ?: return@forEach
                bundle.getCharSequence("text")
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { messages.add(it) }
            }
        }
        read(Notification.EXTRA_MESSAGES)
        read(Notification.EXTRA_HISTORIC_MESSAGES)
        return messages.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    companion object {
        private const val TAG = "QingheNotification"
        private const val ACTIVE_SCAN_WINDOW_MS = 10 * 60 * 1000L

        fun component(context: android.content.Context): ComponentName =
            ComponentName(context, PaymentNotificationListener::class.java)
    }
}
