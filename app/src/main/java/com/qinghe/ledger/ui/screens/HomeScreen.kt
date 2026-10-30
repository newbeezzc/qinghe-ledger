package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.BudgetEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.MonthSummary
import com.qinghe.ledger.ui.components.CategoryBadge
import com.qinghe.ledger.ui.components.EmptyState
import com.qinghe.ledger.ui.components.MonthSelector
import com.qinghe.ledger.ui.components.categoryColor
import com.qinghe.ledger.ui.theme.IncomeGreen
import com.qinghe.ledger.ui.theme.QingheGreen
import com.qinghe.ledger.ui.theme.QingheGreenDark
import com.qinghe.ledger.util.AutoPermissionState
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDayHeader
import com.qinghe.ledger.util.formatTime
import com.qinghe.ledger.util.toLocalDate
import java.time.YearMonth

private enum class AmountTone {
    EXPENSE,
    INCOME,
    TRANSFER
}

private sealed interface HomeListItem {
    val key: String

    data class Day(
        override val key: String,
        val label: String,
        val expenseText: String
    ) : HomeListItem

    data class Entry(
        override val key: String,
        val transaction: TransactionEntity,
        val title: String,
        val subtitle: String,
        val amountText: String,
        val tone: AmountTone,
        val emoji: String,
        val badgeColor: Color,
        val auto: Boolean
    ) : HomeListItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    month: YearMonth,
    summary: MonthSummary,
    transactions: List<TransactionEntity>,
    categories: Map<Long, CategoryEntity>,
    accounts: Map<Long, AccountEntity>,
    budget: BudgetEntity?,
    permissions: AutoPermissionState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenAdd: () -> Unit,
    onEdit: (TransactionEntity) -> Unit,
    onDelete: (TransactionEntity) -> Unit,
    onOpenAutoSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var deleteTarget by remember { mutableStateOf<TransactionEntity?>(null) }
    val homeItems = remember(transactions, categories, accounts) {
        buildList {
            transactions.groupBy { it.occurredAt.toLocalDate() }.forEach { (date, dayTransactions) ->
                val dayExpense = dayTransactions
                    .filter { it.type == TransactionType.EXPENSE.name }
                    .sumOf { it.amountCents }
                add(
                    HomeListItem.Day(
                        key = "day-$date",
                        label = formatDayHeader(date),
                        expenseText = if (dayExpense > 0) {
                            "支出 ${MoneyFormat.withSymbol(dayExpense)}"
                        } else {
                            ""
                        }
                    )
                )

                dayTransactions.forEach { transaction ->
                    val type = TransactionType.from(transaction.type)
                    val isTransfer = type == TransactionType.TRANSFER
                    val category = transaction.categoryId?.let { categories[it] }
                    val parent = category?.parentId?.let { categories[it] }
                    val categoryPath = when {
                        parent != null && category != null -> "${parent.name}-${category.name}"
                        category != null -> category.name
                        else -> null
                    }
                    val title = when {
                        isTransfer -> "转账"
                        categoryPath != null -> categoryPath
                        transaction.note.isNotBlank() -> transaction.note
                        else -> "未分类"
                    }
                    val account = accounts[transaction.accountId]
                    val toAccount = transaction.toAccountId?.let { accounts[it] }
                    val auto = transaction.source != TransactionSource.MANUAL.name
                    val subtitle = buildList {
                        if (isTransfer) {
                            add("${account?.name ?: "账户"} → ${toAccount?.name ?: "账户"}")
                            if (transaction.note.isNotBlank()) add(transaction.note)
                        } else {
                            if (transaction.note.isNotBlank()) add(transaction.note)
                            add(account?.name ?: "账户")
                        }
                        if (auto) add("自动")
                        add(formatTime(transaction.occurredAt))
                    }.joinToString(" · ")
                    val amountText = when (type) {
                        TransactionType.EXPENSE -> "-${MoneyFormat.withSymbol(transaction.amountCents)}"
                        TransactionType.INCOME -> "+${MoneyFormat.withSymbol(transaction.amountCents)}"
                        TransactionType.TRANSFER -> MoneyFormat.withSymbol(transaction.amountCents)
                    }
                    val tone = when (type) {
                        TransactionType.INCOME -> AmountTone.INCOME
                        TransactionType.EXPENSE -> AmountTone.EXPENSE
                        TransactionType.TRANSFER -> AmountTone.TRANSFER
                    }
                    add(
                        HomeListItem.Entry(
                            key = "tx-${transaction.id}",
                            transaction = transaction,
                            title = title,
                            subtitle = subtitle,
                            amountText = amountText,
                            tone = tone,
                            emoji = category?.emoji ?: if (isTransfer) "🔄" else "📦",
                            badgeColor = categoryColor(category?.colorHex),
                            auto = auto
                        )
                    )
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "青禾记账",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "把每一笔，记清楚",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "🌱", fontSize = 22.sp)
                }
            }
        }

        item(key = "month") {
            MonthSelector(
                month = month,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        item(key = "summary") {
            SummaryCard(summary = summary, budget = budget)
        }

        if (!permissions.autoReady) {
            item(key = "auto-banner") {
                AutoRecordBanner(onClick = onOpenAutoSettings)
            }
        }

        if (!permissions.systemNotifications) {
            item(key = "notification-banner") {
                NotificationPermissionBanner(onClick = onOpenNotificationSettings)
            }
        }

        if (permissions.autoReady && !permissions.overlay) {
            item(key = "overlay-banner") {
                OverlayPermissionBanner(onClick = onOpenOverlaySettings)
            }
        }

        if (transactions.isEmpty()) {
            item(key = "empty") {
                Column {
                    EmptyState(
                        emoji = "🌱",
                        title = "这个月还没有账目",
                        subtitle = "点右下角「记一笔」，或开启自动记账"
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        FilledTonalButton(onClick = onOpenAdd) {
                            Text("记第一笔")
                        }
                    }
                }
            }
        }

        items(
            items = homeItems,
            key = { it.key },
            contentType = { item ->
                if (item is HomeListItem.Day) "day" else "entry"
            }
        ) { item ->
            when (item) {
                is HomeListItem.Day -> DayHeaderRow(item)
                is HomeListItem.Entry -> TransactionRow(
                    item = item,
                    onClick = { onEdit(item.transaction) },
                    onLongClick = { deleteTarget = item.transaction }
                )
            }
        }
    }

    deleteTarget?.let { target ->
        val targetName = target.note.takeIf { it.isNotBlank() }
            ?: target.merchant?.takeIf { it.isNotBlank() }
            ?: target.categoryId?.let { categories[it]?.name }
            ?: "未命名账目"
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这笔账？") },
            text = { Text("「$targetName」将被永久删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target)
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

@Composable
private fun SummaryCard(summary: MonthSummary, budget: BudgetEntity?) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(QingheGreenDark, QingheGreen, Color(0xFF5E9678))
                )
            )
            .padding(20.dp)
    ) {
        Column {
            Text(
                text = "本月支出",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = MoneyFormat.amount(summary.expenseCents),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(14.dp))
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "收入",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                    Text(
                        text = MoneyFormat.withSymbol(summary.incomeCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "结余",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                    Text(
                        text = MoneyFormat.withSymbol(summary.balanceCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            if (budget != null && budget.amountCents > 0) {
                Spacer(Modifier.height(16.dp))
                val ratio = (summary.expenseCents.toFloat() / budget.amountCents)
                    .coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
                Spacer(Modifier.height(6.dp))
                val remaining = budget.amountCents - summary.expenseCents
                Text(
                    text = if (remaining >= 0) {
                        "本月预算还剩 ${MoneyFormat.withSymbol(remaining)}"
                    } else {
                        "已超支 ${MoneyFormat.withSymbol(-remaining)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun AutoRecordBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "⚡", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开启自动记账",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "微信、支付宝付款后自动入账",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun NotificationPermissionBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🔔", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开启自动记账提醒",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "否则自动记账成功时不会弹出通知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun OverlayPermissionBanner(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🪟", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "开启自动记账弹窗",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "记账后直接打开记录详情，可直接修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DayHeaderRow(item: HomeListItem.Day) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (item.expenseText.isNotBlank()) {
            Text(
                text = item.expenseText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionRow(
    item: HomeListItem.Entry,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val amountColor = when (item.tone) {
        AmountTone.INCOME -> IncomeGreen
        AmountTone.EXPENSE -> MaterialTheme.colorScheme.onSurface
        AmountTone.TRANSFER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(
                emoji = item.emoji,
                color = item.badgeColor,
                size = 42.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.auto) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = "自动记账",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.amountText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = amountColor
            )
        }
    }
}
