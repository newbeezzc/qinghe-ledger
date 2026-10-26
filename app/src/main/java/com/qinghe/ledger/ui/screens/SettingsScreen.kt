package com.qinghe.ledger.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qinghe.ledger.BuildConfig
import com.qinghe.ledger.auto.CaptureDiagnostics
import com.qinghe.ledger.auto.CaptureDiagnosticsState
import com.qinghe.ledger.data.AutoSettings
import com.qinghe.ledger.data.DEFAULT_GITHUB_REPO
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.ui.components.InfoCard
import com.qinghe.ledger.ui.components.NavRow
import com.qinghe.ledger.ui.components.SectionTitle
import com.qinghe.ledger.ui.theme.QingheGreen
import com.qinghe.ledger.util.AutoPermissionState
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime
import com.qinghe.ledger.util.openAccessibilitySettings
import com.qinghe.ledger.util.openAppNotificationSettings
import com.qinghe.ledger.util.openNotificationListenerSettings
import com.qinghe.ledger.util.openOverlaySettings
import com.qinghe.ledger.util.rememberAutoPermissionState

@Composable
fun SettingsScreen(
    settings: AutoSettings,
    latestAuto: TransactionEntity?,
    latestAutoCategoryPath: String?,
    onToggleNotificationAuto: (Boolean) -> Unit,
    onToggleAccessibilityAuto: (Boolean) -> Unit,
    onToggleShowAutoNotification: (Boolean) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSetGithubRepo: (String) -> Unit,
    onToggleAutoCheckUpdate: (Boolean) -> Unit,
    modifier: Modifier = Modifier
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
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showRepoDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "settings-header") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "我的",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
        }
        item(key = "privacy") {
            InfoCard(
                emoji = "🔒",
                title = "数据只在本机",
                message = "账单只保存在这台手机上。联网仅用于检查/下载你配置的 GitHub 更新，不会上传账单或记账内容。"
            )
        }

        item(key = "auto-title") { SectionTitle(text = "自动记账", modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }

        item(key = "permission-notification") {
            PermissionRow(
                emoji = "🔔",
                title = "通知监听",
                subtitle = "读取微信、支付宝的支付通知",
                enabled = permissions.notificationListener,
                onClick = { openNotificationListenerSettings(context) }
            )
        }
        item(key = "permission-accessibility") {
            PermissionRow(
                emoji = "👀",
                title = "支付页面识别",
                subtitle = "读取微信、支付宝的支付结果页面",
                enabled = permissions.accessibility,
                onClick = { openAccessibilitySettings(context) }
            )
        }
        item(key = "permission-overlay") {
            PermissionRow(
                emoji = "🪟",
                title = "悬浮窗提醒",
                subtitle = "记账后在屏幕下方弹出可修改的卡片",
                enabled = permissions.overlay,
                onClick = { openOverlaySettings(context) }
            )
        }
        item(key = "permission-notifications-system") {
            PermissionRow(
                emoji = "📣",
                title = "系统通知权限",
                subtitle = "自动记账成功后的提醒需要此权限",
                enabled = permissions.systemNotifications,
                onClick = { openAppNotificationSettings(context) }
            )
        }

        item(key = "switch-notification") {
            SwitchRow(
                title = "通知自动记账",
                subtitle = "识别到支付通知时自动记一笔",
                checked = settings.notificationAutoEnabled,
                onCheckedChange = onToggleNotificationAuto
            )
        }
        item(key = "switch-accessibility") {
            SwitchRow(
                title = "页面自动记账",
                subtitle = "识别到支付成功页面时自动记一笔",
                checked = settings.accessibilityAutoEnabled,
                onCheckedChange = onToggleAccessibilityAuto
            )
        }
        item(key = "switch-notify") {
            SwitchRow(
                title = "自动记账后提醒",
                subtitle = "在系统通知栏显示刚记录的账目",
                checked = settings.showAutoRecordNotification,
                onCheckedChange = onToggleShowAutoNotification
            )
        }
        item(key = "diagnostics") {
            NavRow(
                emoji = "🩺",
                title = "识别诊断",
                subtitle = "查看无障碍连接与最近捕获的页面文本",
                onClick = { showDiagnosticsDialog = true }
            )
        }

        item(key = "manage-title") { SectionTitle(text = "账本管理", modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
        item(key = "manage-accounts") {
            NavRow(
                emoji = "💳",
                title = "账户管理",
                subtitle = "现金、微信、支付宝、银行卡",
                onClick = onOpenAccounts
            )
        }
        item(key = "manage-categories") {
            NavRow(
                emoji = "🏷️",
                title = "分类管理",
                subtitle = "添加自己的收支分类",
                onClick = onOpenCategories
            )
        }
        item(key = "manage-rules") {
            NavRow(
                emoji = "⚙️",
                title = "自动分类规则",
                subtitle = "按商户关键词自动归类",
                onClick = onOpenRules
            )
        }

        item(key = "about-title") { SectionTitle(text = "关于", modifier = Modifier.padding(top = 6.dp, start = 4.dp)) }
        item(key = "about-update") {
            NavRow(
                emoji = "⬆️",
                title = "检查更新",
                subtitle = "当前 v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}",
                onClick = onCheckUpdate
            )
        }
        item(key = "about-update-source") {
            NavRow(
                emoji = "🐙",
                title = "GitHub 更新源",
                subtitle = settings.githubRepo.ifBlank { "未配置（格式：用户名/仓库名）" },
                onClick = { showRepoDialog = true }
            )
        }
        item(key = "about-auto-update") {
            SwitchRow(
                title = "启动时自动检查更新",
                subtitle = "每天最多检查一次 GitHub Release",
                checked = settings.autoCheckUpdates,
                onCheckedChange = onToggleAutoCheckUpdate
            )
        }
        item(key = "about-help") {
            NavRow(
                emoji = "📖",
                title = "自动记账说明",
                subtitle = "权限用途与识别边界",
                onClick = { showHelpDialog = true }
            )
        }
        item(key = "about-version") {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "版本",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item(key = "footer") {
            Text(
                text = "青禾记账 · 本地优先 · GitHub 更新",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            )
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
                        "3. 自动记录会按关键词规则归类，并可在明细里随时修改或删除。\n\n" +
                        "所有解析都在本机完成，应用不申请联网权限。自动识别仍可能有误，请偶尔核对账目。"
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

    if (showRepoDialog) {
        UpdateSourceDialog(
            current = settings.githubRepo,
            onDismiss = { showRepoDialog = false },
            onSave = { repo ->
                onSetGithubRepo(repo)
                showRepoDialog = false
            }
        )
    }
}

@Composable
private fun UpdateSourceDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember(current) { mutableStateOf(current) }
    val normalized = input.trim()
        .removePrefix("https://github.com/")
        .removePrefix("http://github.com/")
        .trim('/')
    val valid = normalized.isBlank() ||
        normalized.matches(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"""))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("GitHub 更新源") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 120) input = it },
                    label = { Text("用户名/仓库名") },
                    placeholder = { Text(DEFAULT_GITHUB_REPO) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(
                            if (valid) {
                                "仓库需公开，并在 Release 中上传 APK 附件"
                            } else {
                                "格式应为 用户名/仓库名，或完整 GitHub 地址"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "默认：https://github.com/$DEFAULT_GITHUB_REPO（清空即恢复默认）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "更新 APK 必须与当前安装包使用同一签名；签名不一致会被拒绝安装。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(normalized) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
                    text = "提示：页面识别只适用于支付成功页或单笔账单详情页，账单列表不会逐笔记录。诊断会显示最近成功识别、扫描次数以及是否使用了关键词搜索。若一直失败，请检查微信版本（8.0.55–8.0.58 有已知异常）、系统支付保护，或改用通知监听。诊断内容仅保存在内存中，不会上传。",
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

@Composable
private fun PermissionRow(
    emoji: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
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
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
