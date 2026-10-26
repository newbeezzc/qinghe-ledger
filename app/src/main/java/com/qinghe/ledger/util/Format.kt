package com.qinghe.ledger.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object MoneyFormat {
    fun amount(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        val yuan = abs / 100
        val fen = abs % 100
        val grouped = yuan.toString()
            .reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
        return "$sign$grouped.${fen.toString().padStart(2, '0')}"
    }

    fun withSymbol(cents: Long): String =
        if (cents < 0) "-¥${amount(-cents)}" else "¥${amount(cents)}"

    /** Converts user keypad input such as "12.3" into 1230 cents. */
    fun inputToCents(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed == ".") return null
        val parts = trimmed.split(".")
        if (parts.size > 2) return null
        val yuan = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fraction = parts.getOrNull(1).orEmpty()
        val cents = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> fraction.toLongOrNull()?.times(10) ?: return null
            else -> fraction.take(2).toLongOrNull() ?: return null
        }
        return yuan * 100 + cents
    }

    fun centsToInput(cents: Long): String {
        val yuan = cents / 100
        val fen = cents % 100
        return if (fen == 0L) yuan.toString() else "$yuan.${fen.toString().padStart(2, '0')}"
    }
}

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun YearMonth.monthKey(): String =
    format(DateTimeFormatter.ofPattern("yyyy-MM"))

fun YearMonth.displayName(): String =
    format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA))

fun YearMonth.startMillis(): Long = atDay(1).toEpochMillis()

fun YearMonth.endMillis(): Long = plusMonths(1).atDay(1).toEpochMillis()

fun formatDayHeader(date: LocalDate): String {
    val weekday = date.dayOfWeek.value
    val weekdayText = when (weekday) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
    return "${date.monthValue}月${date.dayOfMonth}日 $weekdayText"
}

private val DATE_TIME_FORMATTER =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val TIME_FORMATTER =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DATE_TIME_FORMATTER)

fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMATTER)
