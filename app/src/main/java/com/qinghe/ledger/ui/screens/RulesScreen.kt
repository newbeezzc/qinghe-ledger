package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qinghe.ledger.data.local.AutoRuleEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.ui.components.CategoryBadge
import com.qinghe.ledger.ui.components.InfoCard
import com.qinghe.ledger.ui.components.ManagerTopBar

@Composable
fun RulesScreen(
    rules: List<AutoRuleEntity>,
    categories: List<CategoryEntity>,
    onBack: () -> Unit,
    onAdd: (String, Long) -> Unit,
    onDelete: (AutoRuleEntity) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AutoRuleEntity?>(null) }
    val categoryById = remember(categories) { categories.associateBy { it.id } }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            ManagerTopBar(
                title = "自动分类规则",
                onBack = onBack,
                onAdd = { showAddDialog = true },
                addDescription = "添加规则"
            )
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "rules-tip") {
                    InfoCard(
                        emoji = "⚙️",
                        title = "按关键词自动归类",
                        message = "自动记账时会先匹配你保存的规则，再根据商户名、商品说明和常见平台关键词自动选择小类；判断不了才归入「其他」。"
                    )
                }
                items(rules, key = { it.id }) { rule ->
                    val category = categoryById[rule.categoryId]
                    val parent = category?.parentId?.let { categoryById[it] }
                    val categoryLabel = when {
                        parent != null && category != null -> "${parent.name}-${category.name}"
                        category != null -> category.name
                        else -> "已删除分类"
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 13.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "「${rule.keyword}」",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            CategoryBadge(category = category, size = 30.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = categoryLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (rule.builtIn) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "内置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleteTarget = rule }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "删除规则",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        RuleDialog(
            categories = categories,
            onDismiss = { showAddDialog = false },
            onSave = { keyword, categoryId ->
                onAdd(keyword, categoryId)
                showAddDialog = false
            }
        )
    }

    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条规则？") },
            text = { Text("关键词「${rule.keyword}」将不再自动归类。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(rule)
                        deleteTarget = null
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit
) {
    var keyword by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自动分类规则") },
        text = {
            Column {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { if (it.length <= 20) keyword = it },
                    label = { Text("关键词") },
                    placeholder = { Text("例如：星巴克") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "归入分类",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        categories.forEach { category ->
                            val parent = category.parentId?.let { parentId ->
                                categories.firstOrNull { it.id == parentId }
                            }
                            val label = if (parent != null) {
                                "${parent.name}-${category.name}"
                            } else {
                                category.name
                            }
                            FilterChip(
                                selected = categoryId == category.id,
                                onClick = { categoryId = category.id },
                                label = { Text("${category.emoji} $label") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keyword.isNotBlank() && categoryId != null,
                onClick = { onSave(keyword.trim(), categoryId!!) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
