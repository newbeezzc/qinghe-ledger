package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qinghe.ledger.BuildConfig
import com.qinghe.ledger.data.AutoSettings
import com.qinghe.ledger.ui.components.GroupedNavRow
import com.qinghe.ledger.ui.components.GroupedSwitchRow
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
}
