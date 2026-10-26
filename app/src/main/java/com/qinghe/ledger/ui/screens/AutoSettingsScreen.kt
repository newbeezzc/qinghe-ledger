package com.qinghe.ledger.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qinghe.ledger.BuildConfig
import com.qinghe.ledger.auto.CaptureDiagnostics
import com.qinghe.ledger.auto.CaptureDiagnosticsState
import com.qinghe.ledger.data.AutoSettings
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.ui.components.GroupedNavRow
import com.qinghe.ledger.ui.components.GroupedSwitchRow
import com.qinghe.ledger.ui.components.InfoCard
import com.qinghe.ledger.ui.components.ManagerTopBar
import com.qinghe.ledger.ui.components.SectionTitle
import com.qinghe.ledger.ui.components.SettingsDivider
import com.qinghe.ledger.ui.components.SettingsGroup
import com.qinghe.ledger.ui.theme.QingheGreen
import com.qinghe.ledger.util.AutoPermissionState
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime
import com.qinghe.ledger.util.openAccessibilitySettings
import com.qinghe.ledger.util.openAppNotificationSettings
import com.qinghe.ledger.util.openNotificationListenerSettings
import com.qinghe.ledger.util.openOverlaySettings
import com.qinghe.ledger.util.rememberAutoPermissionState

/**
 * Dedicated page for the auto-capture pipeline. Keeping permissions, toggles
 * and diagnostics here leaves the main "我的" page to navigation entries only.
 */
@Composable
fun AutoSettingsScreen(
    settings: AutoSettings,
    latestAuto: TransactionEntity?,
    latestAutoCategoryPath: String?,
    onBack: () -> Unit,
    onToggleNotificationAuto: (Boolean) -> Unit,
    onToggleAccessibilityAuto: (Boolean) -> Unit,
    onToggleShowAutoNotification: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val permissions = rememberAutoPermissionState()
    val diagnostics by CaptureDiagnostics.state.collectAsStateWithLifecycle()
    val wechatVersion = installedVersion(context, "com.tencent.mm")
    val alipayVersion = installedVersion(context, "com.eg.android.AlipayGphone")
    val wechatAccessibilityBug = wechatVersion.matches(Regex("""^8\.0\.5[5-8].*"""))
    val latestAutoText = latestAuto?.let { transaction ->
        val sourceLabel = when (transaction.source) {
            "NOTIFICATION" -> "通知"
            "ACCESSIBILITY" -> "页面"
            else -> transaction.source
        }
        "${formatDateTime(transaction.occurredAt)} · " +
            "${MoneyFormat.withSymbol(transaction.amountCents)} · " +
            "${latestAutoCategoryPath ?: "未分类"} · $sourceLabel"
    } ?: "无"
    val enabledPermissions = listOf(
        permissions.notificationListener,
        permissions.accessibility,
        permissions.overlay,
        permissions.systemNotifications
    ).count { it }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ManagerTopBar(title = "自动记账", onBack = onBack)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "auto-summary") {
                    InfoCard(
                        emoji = "⚡",
                        title = "在本机识别，不上传",
                        message = "已开启 $enabledPermissions/4 项权限。" +
                            "通知和页面内容只在设备上解析，用于生成本地账目。"
                    )
                }

                item(key = "permission-title") {
                    SectionTitle(
                        text = "权限",
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
                item(key = "permissions") {
                    SettingsGroup {
                        PermissionRow(
                            emoji = "🔔",
                            title = "通知监听",
                            subtitle = "读取微信、支付宝的支付通知",
                            enabled = permissions.notificationListener,
                            onClick = { openNotificationListenerSettings(context) }
                        )
                        SettingsDivider()
                        PermissionRow(
                            emoji = "👀",
                            title = "支付页面识别",
                            subtitle = "读取微信、支付宝的支付成功页",
                            enabled = permissions.accessibility,
                            onClick = { openAccessibilitySettings(context) }
                        )
                        SettingsDivider()
                        PermissionRow(
                            emoji = "🪟",
                            title = "悬浮窗提醒",
                            subtitle = "记账后在屏幕下方弹出可修改的卡片",
                            enabled = permissions.overlay,
                            onClick = { openOverlaySettings(context) }
                        )
                        SettingsDivider()
                        PermissionRow(
                            emoji = "📣",
                            title = "系统通知权限",
                            subtitle = "自动记账成功后的提醒需要此权限",
                            enabled = permissions.systemNotifications,
                            onClick = { openAppNotificationSettings(context) }
                        )
                    }
                }

                item(key = "switch-title") {
                    SectionTitle(
                        text = "自动记账",
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
                item(key = "switches") {
                    SettingsGroup {
                        GroupedSwitchRow(
                            title = "通知自动记账",
                            subtitle = "识别到支付通知时自动记一笔",
                            checked = settings.notificationAutoEnabled,
                            onCheckedChange = onToggleNotificationAuto,
                            emoji = "📨"
                        )
                        SettingsDivider()
                        GroupedSwitchRow(
                            title = "页面自动记账",
                            subtitle = "识别到支付成功页面时自动记一笔",
                            checked = settings.accessibilityAutoEnabled,
                            onCheckedChange = onToggleAccessibilityAuto,
                            emoji = "🧾"
                        )
                        SettingsDivider()
                        GroupedSwitchRow(
                            title = "自动记账后提醒",
                            subtitle = "悬浮卡片，无权限时改发通知",
                            checked = settings.showAutoRecordNotification,
                            onCheckedChange = onToggleShowAutoNotification,
                            emoji = "✨"
                        )
                    }
                }

                item(key = "diagnostics-title") {
                    SectionTitle(
                        text = "诊断与说明",
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
                item(key = "diagnostics") {
                    SettingsGroup {
                        GroupedNavRow(
                            emoji = "🩺",
                            title = "识别诊断",
                            subtitle = "查看服务连接与最近捕获的页面文本",
                            onClick = { showDiagnosticsDialog = true }
                        )
                        SettingsDivider()
                        GroupedNavRow(
                            emoji = "📖",
                            title = "自动记账说明",
                            subtitle = "权限用途与识别边界",
                            onClick = { showHelpDialog = true }
                        )
                    }
                }

                item(key = "auto-footer") {
                    Text(
                        text = "解析全部在本机完成；应用仅在检查更新时访问 GitHub。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("自动记账怎么工作？") },
            text = {
                Text(
                    "1. 通知监听：读取微信「微信支付」和支付宝的支付通知，提取金额与商户。\n\n" +
                        "2. 页面识别：只在你手动开启无障碍权限后，读取微信、支付宝的支付成功页或单笔账单详情页；支付成功页需要成功标题、金额和一个上下文标记（完成、支付方式、零钱等），账单详情页需要交易单号、付款方式等多个标记；聊天内容不会被记录。\n\n" +
                        "3. 自动记录会按商户名、商品说明和规则归入小类，并可在明细里随时修改或删除。\n\n" +
                        "所有解析都在本机完成；应用仅在检查更新时访问 GitHub。自动识别仍可能有误，请偶尔核对账目。"
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("知道了") }
            }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            permissions = permissions,
            diagnostics = diagnostics,
            wechatVersion = wechatVersion,
            alipayVersion = alipayVersion,
            wechatAccessibilityBug = wechatAccessibilityBug,
            latestAutoText = latestAutoText,
            onDismiss = { showDiagnosticsDialog = false }
        )
    }
}

@Composable
private fun PermissionRow(
    emoji: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    GroupedNavRow(
        emoji = emoji,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (enabled) {
                    QingheGreen.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = if (enabled) "已开启" else "去开启",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) QingheGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    )
}

private fun installedVersion(context: Context, packageName: String): String = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(packageName, 0)
    info.versionName ?: "未知"
}.getOrDefault("未安装")

@Composable
private fun DiagnosticsDialog(
    permissions: AutoPermissionState,
    diagnostics: CaptureDiagnosticsState,
    wechatVersion: String,
    alipayVersion: String,
    wechatAccessibilityBug: Boolean,
    latestAutoText: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val copyText = buildString {
        appendLine("青禾记账 ${BuildConfig.VERSION_NAME}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("微信版本：$wechatVersion")
        appendLine("支付宝版本：$alipayVersion")
        appendLine()
        appendLine("通知监听：${permissionLabel(permissions.notificationListener)}")
        appendLine("无障碍服务：${permissionLabel(permissions.accessibility)}")
        appendLine("悬浮窗提醒：${permissionLabel(permissions.overlay)}")
        appendLine("系统通知：${permissionLabel(permissions.systemNotifications)}")
        appendLine("最近自动记账：$latestAutoText")
        appendLine()
        append(diagnostics.toText())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("识别诊断") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "通知监听：${permissionLabel(permissions.notificationListener)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "通知监听连接：${if (diagnostics.notificationListenerConnected) "已连接" else "未连接"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "无障碍服务：${permissionLabel(permissions.accessibility)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "悬浮窗提醒：${permissionLabel(permissions.overlay)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "服务连接：${if (diagnostics.accessibilityConnected) "已连接" else "未连接"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "系统通知：${permissionLabel(permissions.systemNotifications)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "微信版本：$wechatVersion · 支付宝版本：$alipayVersion",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "最近自动记账（已入库）：$latestAutoText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (wechatAccessibilityBug) {
                    Text(
                        text = "微信 8.0.55–8.0.58 存在已知无障碍 UI 树异常，建议升级微信或临时回退 8.0.54 后再试。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = diagnostics.toText(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "提示：页面识别只适用于支付成功页或单笔账单详情页，账单列表不会逐笔记录。若一直失败，请检查微信版本（8.0.55–8.0.58 有已知异常）、系统支付保护，或改用通知监听。诊断内容仅保存在内存中，不会上传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { CaptureDiagnostics.clear() }) {
                    Text("清空记录")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("青禾记账诊断", copyText)
                    )
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                }
            ) { Text("复制诊断") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun permissionLabel(enabled: Boolean): String =
    if (enabled) "已开启" else "未开启"
