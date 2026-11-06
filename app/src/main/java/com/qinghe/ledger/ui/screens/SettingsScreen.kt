package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qinghe.ledger.BuildConfig
import com.qinghe.ledger.data.AutoSettings
import com.qinghe.ledger.data.DEFAULT_GITEE_REPO
import com.qinghe.ledger.data.DEFAULT_GITHUB_REPO
import com.qinghe.ledger.ui.components.GroupedNavRow
import com.qinghe.ledger.ui.components.GroupedSwitchRow
import com.qinghe.ledger.ui.components.GroupedValueRow
import com.qinghe.ledger.ui.components.InfoCard
import com.qinghe.ledger.ui.components.SectionTitle
import com.qinghe.ledger.ui.components.SettingsDivider
import com.qinghe.ledger.ui.components.SettingsGroup
import com.qinghe.ledger.util.rememberAutoPermissionState

@Composable
fun SettingsScreen(
    settings: AutoSettings,
    onOpenAutoSettings: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenRules: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSetGiteeRepo: (String) -> Unit,
    onSetGithubRepo: (String) -> Unit,
    onSetUpdateMirror: (String) -> Unit,
    onToggleAutoCheckUpdate: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val permissions = rememberAutoPermissionState()
    val enabledPermissions = listOf(
        permissions.notificationListener,
        permissions.accessibility,
        permissions.overlay,
        permissions.systemNotifications
    ).count { it }
    val autoSources = when {
        settings.notificationAutoEnabled && settings.accessibilityAutoEnabled -> "通知 + 页面"
        settings.notificationAutoEnabled -> "仅通知"
        settings.accessibilityAutoEnabled -> "仅页面"
        else -> "已关闭"
    }
    var showRepoDialog by remember { mutableStateOf(false) }
    var showGiteeDialog by remember { mutableStateOf(false) }
    var showMirrorDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                message = "账目只保存在这台手机上。联网仅用于检查/下载 Gitee / GitHub 更新，不上传账单、通知或页面文本。"
            )
        }

        item(key = "auto-title") {
            SectionTitle(text = "自动记账", modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        item(key = "auto-entry") {
            SettingsGroup {
                GroupedNavRow(
                    emoji = "⚡",
                    title = "自动记账设置",
                    subtitle = "$enabledPermissions/4 项权限 · $autoSources",
                    onClick = onOpenAutoSettings
                )
            }
        }

        item(key = "ledger-title") {
            SectionTitle(text = "账本", modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        item(key = "ledger-group") {
            SettingsGroup {
                GroupedNavRow(
                    emoji = "💳",
                    title = "账户管理",
                    subtitle = "现金、微信、支付宝、抖音、美团、银行卡",
                    onClick = onOpenAccounts
                )
                SettingsDivider()
                GroupedNavRow(
                    emoji = "🏷️",
                    title = "分类管理",
                    subtitle = "添加自己的收支分类",
                    onClick = onOpenCategories
                )
                SettingsDivider()
                GroupedNavRow(
                    emoji = "⚙️",
                    title = "自动分类规则",
                    subtitle = "按商户/商品智能归入小类",
                    onClick = onOpenRules
                )
            }
        }

        item(key = "about-title") {
            SectionTitle(text = "关于与更新", modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        item(key = "about-group") {
            SettingsGroup {
                GroupedNavRow(
                    emoji = "⬆️",
                    title = "检查更新",
                    subtitle = "当前 v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}",
                    onClick = onCheckUpdate
                )
                SettingsDivider()
                GroupedSwitchRow(
                    title = "启动时自动检查更新",
                    subtitle = "每天最多检查一次 Gitee / GitHub Release",
                    checked = settings.autoCheckUpdates,
                    onCheckedChange = onToggleAutoCheckUpdate,
                    emoji = "🔄"
                )
                SettingsDivider()
                GroupedNavRow(
                    emoji = "🟢",
                    title = "Gitee 更新源（默认）",
                    subtitle = settings.giteeRepo.ifBlank {
                        "未启用（直接使用 GitHub）"
                    },
                    onClick = { showGiteeDialog = true }
                )
                SettingsDivider()
                GroupedNavRow(
                    emoji = "🐙",
                    title = "GitHub 备用源",
                    subtitle = settings.githubRepo.ifBlank { DEFAULT_GITHUB_REPO },
                    onClick = { showRepoDialog = true }
                )
                SettingsDivider()
                GroupedNavRow(
                    emoji = "🛰️",
                    title = "更新镜像",
                    subtitle = settings.updateMirror.ifBlank {
                        "直连失败时自动尝试内置镜像"
                    },
                    onClick = { showMirrorDialog = true }
                )
                SettingsDivider()
                GroupedValueRow(
                    emoji = "ℹ️",
                    title = "版本",
                    value = BuildConfig.VERSION_NAME
                )
            }
        }

        item(key = "footer") {
            Text(
                text = "青禾记账 · 本地优先 · Gitee / GitHub 更新",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 4.dp)
            )
        }
    }

    if (showRepoDialog) {
        UpdateSourceDialog(
            title = "GitHub 备用源",
            current = settings.githubRepo,
            defaultRepo = DEFAULT_GITHUB_REPO,
            host = "github.com",
            restoreDefaultWhenBlank = true,
            onDismiss = { showRepoDialog = false },
            onSave = { repo ->
                onSetGithubRepo(repo)
                showRepoDialog = false
            }
        )
    }

    if (showGiteeDialog) {
        UpdateSourceDialog(
            title = "Gitee 更新源",
            current = settings.giteeRepo,
            defaultRepo = DEFAULT_GITEE_REPO,
            host = "gitee.com",
            restoreDefaultWhenBlank = false,
            onDismiss = { showGiteeDialog = false },
            onSave = { repo ->
                onSetGiteeRepo(repo)
                showGiteeDialog = false
            }
        )
    }

    if (showMirrorDialog) {
        UpdateMirrorDialog(
            current = settings.updateMirror,
            onDismiss = { showMirrorDialog = false },
            onSave = { mirror ->
                onSetUpdateMirror(mirror)
                showMirrorDialog = false
            }
        )
    }
}

@Composable
private fun UpdateMirrorDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember(current) { mutableStateOf(current) }
    val normalized = input.trim()
    val valid = normalized.isEmpty() || (
        (normalized.startsWith("https://") || normalized.startsWith("http://")) &&
            !normalized.contains(' ') &&
            normalized.length <= 200
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新镜像") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 200) input = it },
                    label = { Text("镜像前缀或 {url} 模板") },
                    placeholder = { Text("https://ghfast.top/") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(
                            if (valid) {
                                "留空则自动尝试内置镜像。可填 https://ghfast.top/ 这类前缀，或 https://proxy/?u={url} 这类模板。"
                            } else {
                                "需以 http:// 或 https:// 开头，且不能包含空格"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "镜像只负责加速下载；安装前仍会校验包名、版本和签名，镜像无法替换成其他 APK。",
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

@Composable
private fun UpdateSourceDialog(
    title: String,
    current: String,
    defaultRepo: String,
    host: String,
    restoreDefaultWhenBlank: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var input by remember(current) { mutableStateOf(current) }
    val normalized = input.trim()
        .removePrefix("https://$host/")
        .removePrefix("http://$host/")
        .trim('/')
    val valid = normalized.isBlank() ||
        normalized.matches(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"""))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= 120) input = it },
                    label = { Text("用户名/仓库名") },
                    placeholder = { Text(defaultRepo) },
                    singleLine = true,
                    isError = !valid,
                    supportingText = {
                        Text(
                            when {
                                !valid -> "格式应为 用户名/仓库名，或完整 $host 地址"
                                host == "gitee.com" ->
                                    "仓库需公开，并在 Release 中上传 APK 附件；清空即停用 Gitee"
                                else ->
                                    "仓库需公开，并在 Release 中上传 APK 附件"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (restoreDefaultWhenBlank) {
                        "默认：https://$host/$defaultRepo（清空即恢复默认）"
                    } else {
                        "示例：https://$host/$defaultRepo（清空即停用 Gitee）"
                    },
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
