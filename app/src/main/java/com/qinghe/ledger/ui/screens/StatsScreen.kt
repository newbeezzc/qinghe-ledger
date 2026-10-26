package com.qinghe.ledger.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.components.EmptyState
import com.qinghe.ledger.ui.components.MonthSelector
import com.qinghe.ledger.ui.components.PillSelector
import com.qinghe.ledger.ui.components.SectionTitle
import com.qinghe.ledger.ui.components.categoryColor
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.toLocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

private data class CategorySlice(
    val category: CategoryEntity?,
    val amountCents: Long
)

@Composable
fun StatsScreen(
    month: YearMonth,
    transactions: List<TransactionEntity>,
    categories: Map<Long, CategoryEntity>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableIntStateOf(0) }
    val type = if (mode == 0) TransactionType.EXPENSE else TransactionType.INCOME
    val filtered = remember(transactions, mode) {
        transactions.filter { it.type == type.name }
    }
    val total = remember(filtered) { filtered.sumOf { it.amountCents } }
    val slices = remember(filtered, categories) {
        filtered.groupBy { transaction ->
            val category = transaction.categoryId?.let { categories[it] }
            category?.parentId ?: category?.id
        }
            .map { (topCategoryId, rows) ->
                CategorySlice(
                    category = topCategoryId?.let { categories[it] },
                    amountCents = rows.sumOf { it.amountCents }
                )
            }
            .sortedByDescending { it.amountCents }
    }
    val displaySlices = remember(slices) {
        if (slices.size > 7) {
            slices.take(6) + CategorySlice(
                category = null,
                amountCents = slices.drop(6).sumOf { it.amountCents }
            )
        } else {
            slices
        }
    }

    val dailyTotals = remember(filtered, month) {
        val days = month.lengthOfMonth()
        val result = LongArray(days)
        filtered.forEach { transaction ->
            val date = transaction.occurredAt.toLocalDate()
            if (date.year == month.year && date.monthValue == month.monthValue) {
                result[date.dayOfMonth - 1] += transaction.amountCents
            }
        }
        result
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "stats-header") {
            Text(
                text = "统计",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item(key = "stats-month") {
            MonthSelector(month = month, onPrevious = onPreviousMonth, onNext = onNextMonth)
        }
        item(key = "stats-mode") {
            PillSelector(
                options = listOf("支出", "收入"),
                selectedIndex = mode,
                onSelected = { mode = it }
            )
        }

        item(key = "stats-donut") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (mode == 0) "支出构成" else "收入构成",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    if (total <= 0) {
                        EmptyState(
                            emoji = "📊",
                            title = "暂时没有数据",
                            subtitle = "记几笔账后，这里会显示分类占比"
                        )
                    } else {
                        DonutChart(
                            slices = displaySlices,
                            totalCents = total,
                            centerLabel = if (mode == 0) "本月支出" else "本月收入"
                        )
                        Spacer(Modifier.height(16.dp))
                        displaySlices.forEach { slice ->
                            CategoryShareRow(slice = slice, totalCents = total)
                        }
                    }
                }
            }
        }

        item(key = "stats-daily") {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    SectionTitle(text = if (mode == 0) "每日支出" else "每日收入")
                    Spacer(Modifier.height(10.dp))
                    if (total <= 0) {
                        Text(
                            text = "暂无数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        DailyBarChart(
                            dailyCents = dailyTotals,
                            color = if (mode == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            }
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "1日",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${month.lengthOfMonth() / 2}日",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${month.lengthOfMonth()}日",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(
    slices: List<CategorySlice>,
    totalCents: Long,
    centerLabel: String
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier.size(198.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 27.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            var startAngle = -90f
            slices.forEach { slice ->
                val sweep = if (totalCents > 0) {
                    slice.amountCents.toFloat() / totalCents * 360f
                } else {
                    0f
                }
                if (sweep > 0.3f) {
                    drawArc(
                        color = categoryColor(slice.category?.colorHex),
                        startAngle = startAngle + 0.8f,
                        sweepAngle = (sweep - 1.6f).coerceAtLeast(0.4f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokeWidth)
                    )
                }
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "¥${MoneyFormat.amount(totalCents)}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CategoryShareRow(slice: CategorySlice, totalCents: Long) {
    val share = if (totalCents > 0) {
        slice.amountCents.toFloat() / totalCents
    } else {
        0f
    }
    val color = categoryColor(slice.category?.colorHex)
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = slice.category?.emoji?.plus(" ") ?: "📦 ",
                fontSize = 14.sp
            )
            Text(
                text = slice.category?.name ?: "其他分类",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = String.format(Locale.CHINA, "%.1f%%", share * 100f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "¥${MoneyFormat.amount(slice.amountCents)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(5.dp))
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
private fun DailyBarChart(dailyCents: LongArray, color: Color) {
    val maxValue = dailyCents.maxOrNull() ?: 0L
    Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
        if (maxValue <= 0 || dailyCents.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val slot = size.width / dailyCents.size
        val barWidth = (slot - gap).coerceAtLeast(1.5.dp.toPx())
        val chartHeight = size.height - 4.dp.toPx()
        dailyCents.forEachIndexed { index, value ->
            if (value <= 0) return@forEachIndexed
            val ratio = value.toFloat() / maxValue
            val barHeight = (chartHeight * ratio).coerceAtLeast(3.dp.toPx())
            val left = index * slot + (slot - barWidth) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * ratio),
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    barWidth / 2f,
                    barWidth / 2f
                )
            )
        }
    }
}
