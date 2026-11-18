package com.qinghe.ledger.data

import com.qinghe.ledger.auto.PaymentType
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class ParsedQuickText(
    val amountCents: Long?,
    val type: PaymentType,
    val occurredAtMillis: Long,
    val note: String,
    val reason: String
)

/**
 * Parses one line of free-form Chinese into a bookkeeping draft, e.g.
 * "午饭 25", "打车18.5", "昨天买奶茶12", "工资 8000".
 *
 * Pure Kotlin so it can be unit tested on the JVM. Category resolution happens
 * in `LedgerRepository`, which has the category/rule lists.
 */
object QuickTextParser {
    private val amountToken = Regex(
        """(?<![0-9])([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)(?![0-9])"""
    )
    private val datePattern = Regex(
        """\d{1,4}\s*[-/.年]\s*\d{1,2}\s*[-/.月]\s*\d{1,2}\s*日?"""
    )
    private val timePattern = Regex("""\d{1,2}\s*:\s*\d{2}""")
    private val signedPlus = Regex("""(?<![0-9])\+\s*[¥￥]?\s*[0-9]""")
    private val signedMinus = Regex("""(?<![0-9])-\s*[¥￥]?\s*[0-9]""")
    private val relativeDate = Regex("""(昨天|前天|今天)""")

    private val incomeWords = listOf(
        "工资", "薪水", "薪资", "收入", "奖金", "绩效", "年终奖",
        "报销", "退款", "退回", "收到", "收款", "到账", "入账",
        "利息", "分红", "收益", "兼职", "稿费", "劳务", "外快", "转入"
    )
    private val labelWords = listOf(
        "花", "买", "支付", "付款", "付", "收入", "工资", "支出",
        "消费", "充值", "收", "退", "转"
    )
    private val excludedSuffixes = listOf(
        "年", "月", "日", "号", "点", "时", "分", "秒", "个", "杯", "份",
        "人", "次", "天", "小时", "折", "%", "公里", "km", "斤", "克",
        "楼", "室", "岁", "年级", "班"
    )
    private val excludedPrefixes = listOf(
        "订单号", "交易单号", "单号", "手机号", "电话", "尾号", "编号",
        "卡号", "账号", "身份证", "密码", "第", "周", "星期"
    )
    private val discountPrefixes = listOf(
        "优惠金额", "满减金额", "减免金额", "优惠", "立减", "满减", "减免",
        "折扣", "抵扣", "已减", "优惠券", "红包抵扣", "满", "减"
    )

    private data class Candidate(
        val cents: Long,
        val start: Int,
        val score: Int
    )

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis()): ParsedQuickText {
        val text = normalize(raw)
        val amount = extractAmount(text)
        return ParsedQuickText(
            amountCents = amount,
            type = detectType(text),
            occurredAtMillis = resolveDate(text, nowMillis),
            note = raw.trim().take(80),
            reason = if (amount == null) {
                "未找到金额，请加上数字，例如：午饭 25"
            } else {
                "识别成功"
            }
        )
    }

    private fun normalize(raw: String): String {
        val out = StringBuilder(raw.length)
        raw.forEach { ch ->
            when (ch) {
                in '０'..'９' -> out.append('0' + (ch - '０'))
                '，' -> out.append(',')
                '．' -> out.append('.')
                '：' -> out.append(':')
                '＋' -> out.append('+')
                '－', '−' -> out.append('-')
                '％' -> out.append('%')
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }

    private fun extractAmount(text: String): Long? {
        if (text.isEmpty()) return null
        val excludedRanges = buildList {
            datePattern.findAll(text).forEach { add(it.range) }
            timePattern.findAll(text).forEach { add(it.range) }
        }
        val candidates = amountToken.findAll(text).mapNotNull { match ->
            if (excludedRanges.any { range ->
                    match.range.first >= range.first && match.range.last <= range.last
                }
            ) {
                return@mapNotNull null
            }
            val cents = toCents(match.groupValues[1]) ?: return@mapNotNull null
            val before = text.substring(maxOf(0, match.range.first - 10), match.range.first)
                .trimEnd(' ', '　', ':', '：')
            val after = text.substring(
                match.range.last + 1,
                minOf(text.length, match.range.last + 7)
            ).trimStart()
            if (excludedPrefixes.any { before.endsWith(it) }) return@mapNotNull null
            if (discountPrefixes.any { before.endsWith(it) }) return@mapNotNull null
            if (excludedSuffixes.any { after.startsWith(it) }) return@mapNotNull null

            val currencyPrefix = before.endsWith("¥") || before.endsWith("￥") ||
                before.contains("人民币") || before.contains("RMB", ignoreCase = true) ||
                before.contains("CNY", ignoreCase = true)
            val currencySuffix = listOf("元", "块", "圆").any { after.startsWith(it) }
            val labeled = labelWords.any { before.contains(it) }
            val decimal = match.groupValues[1].contains('.')
            val score = when {
                currencyPrefix || currencySuffix -> 3
                labeled || decimal -> 2
                else -> 1
            }
            Candidate(cents = cents, start = match.range.first, score = score)
        }.toList()
        return candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }.thenBy { it.start }
        )?.cents
    }

    private fun toCents(value: String): Long? {
        val parts = value.replace(",", "").split(".")
        val yuan = parts[0].toLongOrNull() ?: return null
        val cents = if (parts.size > 1) {
            (parts[1] + "00").take(2).toLongOrNull() ?: return null
        } else {
            0L
        }
        val total = yuan * 100 + cents
        if (total <= 0L || total > 100_000_000L) return null
        return total
    }

    private fun detectType(text: String): PaymentType {
        if (signedPlus.containsMatchIn(text)) return PaymentType.INCOME
        if (signedMinus.containsMatchIn(text)) return PaymentType.EXPENSE
        if (text.contains("红包")) {
            if (Regex("""(收到|收了|领了|领取|抢到|抢了).{0,4}红包""")
                    .containsMatchIn(text)
            ) {
                return PaymentType.INCOME
            }
            if (Regex("""(发|给|派).{0,4}红包""").containsMatchIn(text)) {
                return PaymentType.EXPENSE
            }
        }
        if (incomeWords.any { text.contains(it) }) return PaymentType.INCOME
        return PaymentType.EXPENSE
    }

    private fun resolveDate(text: String, nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val date = when (relativeDate.find(text)?.value) {
            "昨天" -> now.toLocalDate().minusDays(1)
            "前天" -> now.toLocalDate().minusDays(2)
            else -> return nowMillis
        }
        // 15:00 avoids the meal-time heuristic; explicit 午饭/晚饭 hints still win.
        return date.atTime(LocalTime.of(15, 0))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}
