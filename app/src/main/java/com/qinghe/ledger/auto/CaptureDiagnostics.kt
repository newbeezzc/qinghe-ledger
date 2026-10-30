package com.qinghe.ledger.auto

import com.qinghe.ledger.util.formatDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptureDiagnosticsState(
    val accessibilityConnected: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val screenEventCount: Int = 0,
    val notificationEventCount: Int = 0,
    val lastScreenAt: Long = 0L,
    val lastScreenPackage: String = "",
    val lastScreenText: String = "",
    val lastScreenParsed: Boolean? = null,
    val lastScreenReason: String = "",
    val lastScreenAttempt: Int = 0,
    val lastScreenUsedTargeted: Boolean = false,
    val lastScreenSuccessAt: Long = 0L,
    val lastScreenSuccessPackage: String = "",
    val lastScreenSuccessText: String = "",
    val lastScreenSuccessReason: String = "",
    val lastScreenSuccessAttempt: Int = 0,
    val lastScreenSuccessUsedTargeted: Boolean = false,
    val lastNotificationAt: Long = 0L,
    val lastNotificationPackage: String = "",
    val lastNotificationText: String = "",
    val lastNotificationParsed: Boolean? = null,
    val lastNotificationReason: String = ""
) {
    fun toText(): String = buildString {
        appendLine("无障碍服务连接：${if (accessibilityConnected) "是" else "否"}")
        appendLine("通知监听连接：${if (notificationListenerConnected) "是" else "否"}")
        appendLine("累计支付页面扫描：$screenEventCount")
        appendLine("累计支付通知：$notificationEventCount")
        appendLine()

        appendLine("最近成功识别：")
        if (lastScreenSuccessAt == 0L) {
            appendLine("无")
        } else {
            appendLine("时间：${formatDateTime(lastScreenSuccessAt)}")
            appendLine("应用：${lastScreenSuccessPackage.ifBlank { "未知" }}")
            appendLine("扫描：第 $lastScreenSuccessAttempt 次")
            appendLine("关键词搜索：${if (lastScreenSuccessUsedTargeted) "是" else "否"}")
            appendLine("页面文本：")
            appendLine(lastScreenSuccessText.ifBlank { "（空）" })
        }
        appendLine()

        appendLine("最近页面事件：")
        if (lastScreenAt == 0L) {
            appendLine("无")
        } else {
            appendLine("时间：${formatDateTime(lastScreenAt)}")
            appendLine("应用：${lastScreenPackage.ifBlank { "未知" }}")
            appendLine("结果：${if (lastScreenParsed == true) "识别成功" else "未识别"}")
            appendLine("原因：$lastScreenReason")
            appendLine("扫描：第 $lastScreenAttempt 次")
            appendLine("关键词搜索：${if (lastScreenUsedTargeted) "是" else "否"}")
            appendLine("页面文本：")
            appendLine(lastScreenText.ifBlank { "（空）" })
        }
        appendLine()

        appendLine("最近支付通知：")
        if (lastNotificationAt == 0L) {
            appendLine("无")
        } else {
            appendLine("时间：${formatDateTime(lastNotificationAt)}")
            appendLine("应用：${lastNotificationPackage.ifBlank { "未知" }}")
            appendLine("结果：${if (lastNotificationParsed == true) "识别成功" else "未识别"}")
            appendLine("原因：$lastNotificationReason")
            appendLine("通知文本：")
            appendLine(lastNotificationText.ifBlank { "（空）" })
        }
    }
}

/**
 * In-memory diagnostics for troubleshooting auto-capture on real devices.
 * Nothing is persisted or uploaded.
 */
object CaptureDiagnostics {
    private val _state = MutableStateFlow(CaptureDiagnosticsState())
    val state: StateFlow<CaptureDiagnosticsState> = _state.asStateFlow()

    fun setAccessibilityConnected(connected: Boolean) {
        _state.update { it.copy(accessibilityConnected = connected) }
    }

    fun setNotificationListenerConnected(connected: Boolean) {
        _state.update { it.copy(notificationListenerConnected = connected) }
    }

    fun recordScreen(
        pkg: String,
        text: String,
        parsed: Boolean,
        reason: String,
        attempt: Int = 1,
        usedTargetedSearch: Boolean = false
    ) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val hasSuccess = current.lastScreenSuccessAt > 0L
            // Once a page has been recognised, do not let later empty/failed
            // scans obscure the success in the primary "最近页面事件" section.
            val shouldUpdateLatest = parsed || !hasSuccess
            val latest = if (shouldUpdateLatest) {
                current.copy(
                    screenEventCount = current.screenEventCount + 1,
                    lastScreenAt = now,
                    lastScreenPackage = pkg,
                    lastScreenText = text.take(1200),
                    lastScreenParsed = parsed,
                    lastScreenReason = reason,
                    lastScreenAttempt = attempt,
                    lastScreenUsedTargeted = usedTargetedSearch
                )
            } else {
                current
            }
            if (parsed) {
                latest.copy(
                    lastScreenSuccessAt = now,
                    lastScreenSuccessPackage = pkg,
                    lastScreenSuccessText = text.take(1200),
                    lastScreenSuccessReason = reason,
                    lastScreenSuccessAttempt = attempt,
                    lastScreenSuccessUsedTargeted = usedTargetedSearch
                )
            } else {
                latest
            }
        }
    }

    fun recordNotification(pkg: String, text: String, parsed: Boolean, reason: String) {
        _state.update {
            it.copy(
                notificationEventCount = it.notificationEventCount + 1,
                lastNotificationAt = System.currentTimeMillis(),
                lastNotificationPackage = pkg,
                lastNotificationText = text.take(1200),
                lastNotificationParsed = parsed,
                lastNotificationReason = reason
            )
        }
    }

    fun clear() {
        _state.update {
            CaptureDiagnosticsState(
                accessibilityConnected = it.accessibilityConnected,
                notificationListenerConnected = it.notificationListenerConnected
            )
        }
    }
}
