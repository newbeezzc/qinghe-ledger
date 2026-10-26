package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.components.CategoryBadge
import com.qinghe.ledger.ui.components.ManagerTopBar
import com.qinghe.ledger.ui.components.PillSelector

private val emojiChoices = listOf(
    // 餐饮 / 饮品
    "🍜", "🍚", "🍔", "🍕", "🍣", "🍱", "🍲", "🥐", "🍞", "🥗",
    "🍰", "🍦", "🍫", "🍬", "🍪", "🥡", "🍎", "🍉", "🍇", "🍓",
    "☕", "🧋", "🍵", "🥤", "🧃", "🥛", "🍺", "🍷", "🥂", "🍹",
    // 购物 / 服饰
    "🛒", "🛍️", "👕", "👖", "👗", "👟", "👠", "👜", "👛", "🧢",
    "💄", "🧴", "💍", "🎁", "🧸", "👓", "⌚", "💎",
    // 交通 / 出行
    "🚌", "🚇", "🚕", "🚗", "🚙", "🚲", "🛵", "🏍️", "🚄", "🚂",
    "✈️", "🚢", "⛽", "🅿️", "🧭", "🗺️", "🎒",
    // 居住 / 生活
    "🏠", "🏢", "🛋️", "🛏️", "🪑", "💡", "💧", "🔥", "🪴", "🧹",
    "🧺", "🧼", "🪥", "🧻", "🔑", "🔒", "🚿", "🛠️",
    // 数码 / 通讯
    "📱", "💻", "🖥️", "⌨️", "🖱️", "🎧", "📷", "🔋", "💾", "📶",
    "🌐", "📡",
    // 娱乐 / 运动
    "🎮", "🎬", "🎵", "🎤", "🎫", "🎳", "🎯", "🎲", "♟️", "🏀",
    "⚽", "🏸", "🏊", "🏃", "🚴", "🎣",
    // 医疗 / 健康
    "💊", "🏥", "🩺", "🦷", "💉", "🩹", "🧘", "🏋️",
    // 学习 / 工作
    "📚", "📖", "✏️", "🖊️", "📝", "🎓", "💼", "📊", "🧾", "📦",
    // 收入 / 理财
    "💰", "💵", "💳", "🪙", "🏦", "📈", "💹", "🧧",
    // 宠物 / 自然 / 其他
    "🐶", "🐱", "🐟", "🌱", "🌸", "🌳", "🍀", "☀️", "🌙", "❤️",
    "🎉", "🎊", "🎂", "🏅", "🔧", "⚙️", "🗑️"
).distinct()

@Composable
fun CategoryManagerScreen(
    categories: List<CategoryEntity>,
    onBack: () -> Unit,
    onAdd: (String, String, TransactionType, Long?) -> Unit,
    onUpdate: (CategoryEntity) -> Unit,
    onDelete: (CategoryEntity) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    val type = if (mode == 0) TransactionType.EXPENSE else TransactionType.INCOME
    val topLevel = remember(categories, mode) {
        categories.filter { it.type == type.name && it.parentId == null }
    }
    val visible = remember(categories, mode, topLevel) {
        val children = categories
            .filter { it.type == type.name && it.parentId != null }
            .groupBy { it.parentId }
        topLevel.flatMap { parent -> listOf(parent) + children[parent.id].orEmpty() }
    }

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
                title = "分类管理",
                onBack = onBack,
                onAdd = { showAddDialog = true },
                addDescription = "添加分类"
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                PillSelector(
                    options = listOf("支出分类", "收入分类"),
                    selectedIndex = mode,
                    onSelected = { mode = it }
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visible, key = { it.id }) { category ->
                    val isChild = category.parentId != null
                    Surface(
                        onClick = {
                            if (!category.builtIn) editingCategory = category
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                start = if (isChild) 30.dp else 13.dp,
                                end = 4.dp,
                                top = if (isChild) 7.dp else 9.dp,
                                bottom = if (isChild) 7.dp else 9.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CategoryBadge(category = category, size = if (isChild) 32.dp else 38.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = if (isChild) "↳ ${category.name}" else category.name,
                                style = if (isChild) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = buildString {
                                    if (isChild) append("小类 · ")
                                    append(if (category.builtIn) "内置" else "自定义")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!category.builtIn) {
                                IconButton(onClick = { deleteTarget = category }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除分类",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CategoryDialog(
            category = null,
            type = type,
            parents = topLevel,
            onDismiss = { showAddDialog = false },
            onSave = { name, emoji, parentId ->
                onAdd(name, emoji, type, parentId)
                showAddDialog = false
            }
        )
    }

    editingCategory?.let { category ->
        CategoryDialog(
            category = category,
            type = type,
            parents = topLevel,
            onDismiss = { editingCategory = null },
            onSave = { name, emoji, _ ->
                onUpdate(category.copy(name = name, emoji = emoji))
                editingCategory = null
            }
        )
    }

    deleteTarget?.let { category ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类？") },
            text = { Text("「${category.name}」将被删除。已有账目使用的分类无法删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(category)
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
private fun CategoryDialog(
    category: CategoryEntity?,
    type: TransactionType,
    parents: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit
) {
    var name by remember(category) { mutableStateOf(category?.name.orEmpty()) }
    var emoji by remember(category) {
        mutableStateOf(category?.emoji ?: if (type == TransactionType.INCOME) "💰" else "🍜")
    }
    var parentId by remember(category) { mutableStateOf(category?.parentId) }
    val parent = parents.firstOrNull { it.id == parentId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    category != null -> "编辑分类"
                    parentId != null -> "添加小类"
                    type == TransactionType.EXPENSE -> "添加支出大类"
                    else -> "添加收入大类"
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 12) name = it },
                    label = { Text("分类名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (category == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "所属大类",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilterChip(
                            selected = parentId == null,
                            onClick = { parentId = null },
                            label = { Text("无（新大类）") }
                        )
                        parents.forEach { item ->
                            FilterChip(
                                selected = parentId == item.id,
                                onClick = { parentId = item.id },
                                label = { Text("${item.emoji} ${item.name}") }
                            )
                        }
                    }
                } else if (parent != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "所属大类：${parent.emoji} ${parent.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "选择图标",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        emojiChoices.forEach { choice ->
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (choice == emoji) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        }
                                    )
                                    .clickable { emoji = choice },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = choice, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), emoji, parentId) }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
