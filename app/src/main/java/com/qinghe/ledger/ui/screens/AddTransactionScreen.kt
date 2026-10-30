package com.qinghe.ledger.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.components.CategoryBadge
import com.qinghe.ledger.ui.components.PillSelector
import com.qinghe.ledger.ui.theme.IncomeGreen
import com.qinghe.ledger.util.MoneyFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTransactionScreen(
    existing: TransactionEntity?,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    accountBalances: Map<Long, Long>,
    defaultAccountId: Long?,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onValidationError: (String) -> Unit,
    autoConfirm: Boolean = false
) {
    val context = LocalContext.current
    val initialDateTime = remember(existing) {
        existing?.let {
            Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        } ?: LocalDateTime.now()
    }
    var selectedTypeIndex by remember(existing) {
        mutableStateOf(
            when (TransactionType.from(existing?.type)) {
                TransactionType.EXPENSE -> 0
                TransactionType.INCOME -> 1
                TransactionType.TRANSFER -> 2
            }
        )
    }
    var amountInput by remember(existing) {
        mutableStateOf(existing?.let { MoneyFormat.centsToInput(it.amountCents) } ?: "0")
    }
    var categoryId by remember(existing) { mutableStateOf(existing?.categoryId) }
    var parentCategoryId by remember(existing) { mutableStateOf<Long?>(null) }
    var accountId by remember(existing) {
        mutableStateOf(existing?.accountId ?: defaultAccountId)
    }
    var toAccountId by remember(existing) { mutableStateOf(existing?.toAccountId) }
    var note by remember(existing) { mutableStateOf(existing?.note.orEmpty()) }
    var selectedDate by remember(existing) { mutableStateOf(initialDateTime.toLocalDate()) }
    var selectedTime by remember(existing) { mutableStateOf(initialDateTime.toLocalTime()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val type = when (selectedTypeIndex) {
        0 -> TransactionType.EXPENSE
        1 -> TransactionType.INCOME
        else -> TransactionType.TRANSFER
    }
    val categoriesForType = remember(categories, selectedTypeIndex) {
        categories.filter { it.type == type.name }
    }
    val topLevelCategories = remember(categoriesForType) {
        categoriesForType.filter { it.parentId == null }
    }
    val childrenByParent = remember(categories) {
        categories.groupBy { it.parentId }
    }
    val selectedCategory = categories.firstOrNull { it.id == categoryId }
    val selectedParent = topLevelCategories.firstOrNull { it.id == parentCategoryId }
    val subcategories = selectedParent?.let { parent ->
        childrenByParent[parent.id].orEmpty()
    } ?: emptyList()
    val selectedCategoryPath = selectedCategory?.let { item ->
        val parent = item.parentId?.let { id -> categories.firstOrNull { it.id == id } }
        if (parent != null) "${parent.name}-${item.name}" else item.name
    }
    val categoryLabel = when {
        selectedCategoryPath != null -> selectedCategoryPath
        selectedParent != null && subcategories.isNotEmpty() -> "${selectedParent.name} · 请选择小类"
        else -> "请选择分类"
    }
    val account = accounts.firstOrNull { it.id == accountId }

    LaunchedEffect(existing?.categoryId, categories) {
        if (existing?.categoryId != null) {
            val current = categories.firstOrNull { it.id == existing.categoryId }
            if (current != null) {
                parentCategoryId = current.parentId ?: current.id
            }
        }
    }

    LaunchedEffect(selectedTypeIndex, categories) {
        if (selectedTypeIndex != 2 && categoryId != null && categories.isNotEmpty()) {
            val current = categories.firstOrNull { it.id == categoryId }
            if (current == null || current.type != type.name) {
                categoryId = null
                parentCategoryId = null
            } else {
                parentCategoryId = current.parentId ?: current.id
            }
        }
    }

    LaunchedEffect(accounts, selectedTypeIndex) {
        if (accounts.isNotEmpty()) {
            val from = accountId?.takeIf { id -> accounts.any { it.id == id } }
                ?: defaultAccountId?.takeIf { id -> accounts.any { it.id == id } }
                ?: accounts.first().id
            accountId = from
            if (selectedTypeIndex == 2) {
                if (toAccountId == null || toAccountId == from || accounts.none { it.id == toAccountId }) {
                    toAccountId = accounts.firstOrNull { it.id != from }?.id ?: from
                }
            }
        }
    }

    fun updateAmount(key: String) {
        amountInput = when (key) {
            "DEL" -> amountInput.dropLast(1).ifEmpty { "0" }
            "." -> when {
                amountInput.contains(".") -> amountInput
                amountInput == "0" -> "0."
                else -> "$amountInput."
            }
            else -> {
                val next = if (amountInput == "0") key else amountInput + key
                val parts = next.split(".")
                val integerLength = parts[0].length
                val fractionLength = parts.getOrNull(1)?.length ?: 0
                if (integerLength <= 7 && fractionLength <= 2) next else amountInput
            }
        }
    }

    fun submit() {
        val cents = MoneyFormat.inputToCents(amountInput)
        when {
            cents == null || cents <= 0 -> {
                onValidationError("请输入有效金额")
                return
            }
            type != TransactionType.TRANSFER && parentCategoryId == null -> {
                onValidationError("请选择一个分类")
                return
            }
            type != TransactionType.TRANSFER &&
                subcategories.isNotEmpty() &&
                (categoryId == null || categoryId == parentCategoryId) -> {
                onValidationError("请选择一个小类")
                return
            }
            accountId == null -> {
                onValidationError("请选择账户")
                return
            }
            type == TransactionType.TRANSFER && (toAccountId == null || toAccountId == accountId) -> {
                onValidationError("请选择不同的转入账户")
                return
            }
        }

        val occurredAt = LocalDateTime.of(selectedDate, selectedTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val entity = if (existing != null) {
            existing.copy(
                type = type.name,
                amountCents = cents!!,
                categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                accountId = accountId!!,
                toAccountId = if (type == TransactionType.TRANSFER) toAccountId else null,
                note = note.trim(),
                occurredAt = occurredAt
            )
        } else {
            TransactionEntity(
                type = type.name,
                amountCents = cents!!,
                categoryId = if (type == TransactionType.TRANSFER) null else categoryId,
                accountId = accountId!!,
                toAccountId = if (type == TransactionType.TRANSFER) toAccountId else null,
                note = note.trim(),
                occurredAt = occurredAt,
                source = TransactionSource.MANUAL.name
            )
        }
        onSave(entity)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
                Text(
                    text = when {
                        autoConfirm -> "确认自动记账"
                        existing == null -> "记一笔"
                        else -> "编辑账目"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (autoConfirm && existing != null) {
                    TextButton(onClick = { showDeleteDialog = true }) {
                        Text(
                            text = "取消记录",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                } else if (existing != null) {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (existing != null && existing.source != TransactionSource.MANUAL.name) {
                    Text(
                        text = if (existing.source == TransactionSource.NOTIFICATION.name) {
                            "通知自动"
                        } else {
                            "页面自动"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                PillSelector(
                    options = listOf("支出", "收入", "转账"),
                    selectedIndex = selectedTypeIndex,
                    onSelected = { index ->
                        if (index != selectedTypeIndex) {
                            selectedTypeIndex = index
                            categoryId = null
                            parentCategoryId = null
                        }
                    }
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "¥",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = amountInput,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = when (type) {
                            TransactionType.INCOME -> IncomeGreen
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = categoryLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (type != TransactionType.TRANSFER) {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columns = 5
                        val gap = 4.dp
                        val itemWidth = (maxWidth / columns) - gap - 1.dp
                        FlowRow(
                            maxItemsInEachRow = columns,
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            topLevelCategories.forEach { category ->
                                val selected = category.id == parentCategoryId
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(itemWidth)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                Color.Transparent
                                            }
                                        )
                                        .clickable {
                                            parentCategoryId = category.id
                                            categoryId = if (
                                                childrenByParent[category.id].isNullOrEmpty()
                                            ) {
                                                category.id
                                            } else {
                                                null
                                            }
                                        }
                                        .padding(vertical = 5.dp)
                                ) {
                                    CategoryBadge(category = category, size = 36.dp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    if (selectedParent != null && subcategories.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "小类",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            subcategories.forEach { child ->
                                FilterChip(
                                    selected = child.id == categoryId,
                                    onClick = { categoryId = child.id },
                                    label = { Text("${child.emoji} ${child.name}") }
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "转账不计入收支，只影响账户余额",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (type == TransactionType.TRANSFER) "转出账户" else "账户",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(accounts, key = { it.id }) { item ->
                        FilterChip(
                            selected = item.id == accountId,
                            onClick = { accountId = item.id },
                            label = { Text("${typeEmoji(item.type)} ${item.name}") }
                        )
                    }
                }

                if (type == TransactionType.TRANSFER) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "转入账户",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(accounts, key = { it.id }) { item ->
                            FilterChip(
                                selected = item.id == toAccountId,
                                onClick = { toAccountId = item.id },
                                label = { Text("${typeEmoji(item.type)} ${item.name}") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { if (it.length <= 80) note = it },
                    label = { Text("备注") },
                    placeholder = { Text("可选，例如：和同学聚餐") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    selectedDate = LocalDate.of(year, month + 1, day)
                                },
                                selectedDate.year,
                                selectedDate.monthValue - 1,
                                selectedDate.dayOfMonth
                            ).show()
                        },
                        label = {
                            Text(
                                selectedDate.format(
                                    DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.CalendarToday,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    AssistChip(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute -> selectedTime = LocalTime.of(hour, minute) },
                                selectedTime.hour,
                                selectedTime.minute,
                                true
                            ).show()
                        },
                        label = {
                            Text(
                                selectedTime.format(
                                    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "当前账户余额：${
                        account?.let { item ->
                            accountBalances[item.id]?.let { MoneyFormat.withSymbol(it) }
                        } ?: "—"
                    }",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(214.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf(".", "0", "DEL")
                )
                Column(
                    modifier = Modifier.weight(3f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { key ->
                                Surface(
                                    onClick = { updateAmount(key) },
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (key == "DEL") {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (key == "DEL") {
                                            Icon(
                                                Icons.Rounded.Backspace,
                                                contentDescription = "退格"
                                            )
                                        } else {
                                            Text(
                                                text = key,
                                                style = MaterialTheme.typography.titleLarge
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(
                    onClick = { submit() },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (autoConfirm) "记录" else "保存",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(if (autoConfirm) "取消这笔自动记账？" else "删除这笔账？")
            },
            text = {
                Text(
                    if (autoConfirm) {
                        "取消后，这笔自动记录会被删除。"
                    } else {
                        "删除后无法恢复。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete(existing)
                    }
                ) {
                    Text(
                        text = if (autoConfirm) "取消记录" else "删除",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(if (autoConfirm) "保留" else "取消")
                }
            }
        )
    }
}

private fun typeEmoji(typeName: String): String = when (typeName) {
    "WECHAT" -> "💬"
    "ALIPAY" -> "🐜"
    "DOUYIN" -> "🎵"
    "MEITUAN" -> "🟡"
    "BANK" -> "🏦"
    "CREDIT" -> "💳"
    "CASH" -> "💵"
    else -> "📦"
}
