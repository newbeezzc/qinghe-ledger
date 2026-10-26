package com.qinghe.ledger.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinghe.ledger.data.local.BudgetEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.MonthSummary
import com.qinghe.ledger.ui.components.EmptyState
import com.qinghe.ledger.ui.components.MonthSelector
import com.qinghe.ledger.ui.components.SectionTitle
import com.qinghe.ledger.ui.components.categoryColor
import com.qinghe.ledger.ui.theme.IncomeGreen
import com.qinghe.ledger.ui.theme.QingheGreen
import com.qinghe.ledger.util.MoneyFormat
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun BudgetScreen(
    month: YearMonth,
    budget: BudgetEntity?,
    summary: MonthSummary,
    transactions: List<TransactionEntity>,
    categories: Map<Long, CategoryEntity>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSetBudget: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val expense = summary.expenseCents

    val categorySpending = remember(transactions, categories) {
        transactions
            .filter { it.type == TransactionType.EXPENSE.name }
            .groupBy { transaction ->
                val category = transaction.categoryId?.let { categories[it] }
                category?.parentId ?: category?.id
            }
            .map { (topCategoryId, rows) ->
                (topCategoryId?.let { categories[it] }) to rows.sumOf { it.amountCents }
            }
            .sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "budget-header") {
            Text(
                text = "预算",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item(key = "budget-month") {
            MonthSelector(month = month, onPrevious = onPreviousMonth, onNext = onNextMonth)
        }
        item(key = "budget-card") {
            if (budget == null || budget.amountCents <= 0) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(text = "🌾", fontSize = 28.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "这个月还没有预算",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "先定一个数，花钱更有数",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showDialog = true }) {
                            Text("设置本月预算")
                        }
                    }
                }
            } else {
                val remaining = budget.amountCents - expense
                val ratio = (expense.toFloat() / budget.amountCents).coerceIn(0f, 1f)
                val today = LocalDate.now()
                val currentMonth = YearMonth.from(today)
                val daysLeft = when {
                    month == currentMonth -> month.lengthOfMonth() - today.dayOfMonth + 1
                    month.isAfter(currentMonth) -> month.lengthOfMonth()
                    else -> 1
                }.coerceAtLeast(1)
                val dailyAvailable = (remaining.coerceAtLeast(0) / daysLeft)

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (remaining >= 0) "剩余可用" else "已超支",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { showDialog = true }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "修改预算",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = MoneyFormat.withSymbol(kotlin.math.abs(remaining)),
                            style = MaterialTheme.typography.displaySmall,
                            color = if (remaining >= 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(ratio)
                                    .height(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (ratio >= 1f) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            QingheGreen
                                        }
                                    )
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            BudgetStat(
                                label = "已用",
                                value = MoneyFormat.withSymbol(expense),
                                modifier = Modifier.weight(1f)
                            )
                            BudgetStat(
                                label = "预算",
                                value = MoneyFormat.withSymbol(budget.amountCents),
                                modifier = Modifier.weight(1f)
                            )
                            BudgetStat(
                                label = "日均可用",
                                value = MoneyFormat.withSymbol(dailyAvailable),
                                valueColor = if (dailyAvailable > 0) IncomeGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item(key = "budget-categories") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionTitle(text = "本月支出分类")
                    Spacer(Modifier.height(8.dp))
                    if (categorySpending.isEmpty()) {
                        EmptyState(
                            emoji = "🍃",
                            title = "还没有支出",
                            subtitle = "本月记录支出后，这里会显示分类花费"
                        )
                    } else {
                        categorySpending.take(8).forEach { (category, amount) ->
                            CategorySpendingRow(
                                category = category,
                                amountCents = amount,
                                totalCents = expense
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        BudgetDialog(
            existing = budget,
            onDismiss = { showDialog = false },
            onSave = {
                onSetBudget(it)
                showDialog = false
            }
        )
    }
}

@Composable
private fun BudgetStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

@Composable
private fun CategorySpendingRow(
    category: CategoryEntity?,
    amountCents: Long,
    totalCents: Long
) {
    val share = if (totalCents > 0) amountCents.toFloat() / totalCents else 0f
    val color = categoryColor(category?.colorHex)
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = category?.emoji ?: "📦", fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = category?.name ?: "未分类",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = MoneyFormat.withSymbol(amountCents),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun BudgetDialog(
    existing: BudgetEntity?,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    var input by remember {
        mutableStateOf(existing?.let { MoneyFormat.centsToInput(it.amountCents) } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "设置本月预算" else "修改本月预算") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.matches(Regex("^\\d{0,7}(\\.\\d{0,2})?$"))) {
                            input = value
                        }
                    },
                    label = { Text("预算金额") },
                    prefix = { Text("¥ ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (existing != null) {
                    TextButton(onClick = { onSave(0L) }) {
                        Text("清除预算", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cents = MoneyFormat.inputToCents(input)
                    if (cents != null && cents > 0) onSave(cents)
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
