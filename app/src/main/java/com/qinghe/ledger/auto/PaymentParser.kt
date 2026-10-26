package com.qinghe.ledger.auto

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class PaymentType {
    EXPENSE,
    INCOME
}

data class ParsedPayment(
    val type: PaymentType,
    val amountCents: Long,
    val merchant: String?,
    val rawText: String,
    val occurredAtMillis: Long? = null
)

data class ParseResult(
    val payment: ParsedPayment?,
    val reason: String
)

/**
 * Pure-Kotlin parser for payment notifications and payment result / bill
 * detail screens. Kept free of Android APIs so it can be unit tested on the JVM.
 */
object PaymentParser {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    val supportedPackages = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE)

    private val amountWithSymbol =
        Regex("""[¥￥]\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)""")
    private val amountWithYuan =
        Regex("""([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元""")
    private val amountWithCurrencyCode =
        Regex("""(?:人民币|CNY|RMB)\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val labeledAmount = Regex(
        """(?:交易金额|订单金额|支付金额|付款金额|退款金额|实付金额|合计|金额)[:：]?\s*[¥￥]?\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)"""
    )
    private val standaloneAmount = Regex(
        """(?:^|[\s，,。；;])([−+-]?[0-9]{1,7}(?:,[0-9]{3})*\.[0-9]{2})(?=[\s，,。；;]|$)"""
    )

    private val merchantAfterXiang =
        Regex("""向\s*([^\s，,。；;]{1,24}?)\s*(?:付款|转账)""")
    private val merchantAfterLabel =
        Regex("""(?:收款方|商户全称|商户名称|交易对方|付款给|转账给|商户|对方)[:：]?\s*([^\s，,。；;]{1,24})""")

    private val merchantNoise = setOf(
        "返回", "账单详情", "账单", "详情", "更多", "复制", "投诉", "联系客服",
        "微信支付", "支付宝", "已完成", "支付成功", "交易成功", "当前状态",
        "查看", "全部账单", "服务", "钱包", "零钱", "余额宝", "花呗", "银行卡",
        "转账", "收款", "付款", "退款", "消费", "收入", "支出"
    )

    private val paymentTimeLabels = listOf(
        "支付时间", "付款时间", "交易时间", "创建时间", "下单时间"
    )

    private val incomeWords = listOf(
        "收款成功", "已收款", "收款到账", "收到转账", "收到一笔", "收到红包",
        "已存入零钱", "存入零钱", "到账", "入账", "红包已领取",
        "收入", "退款", "退回"
    )
    private val expenseWords = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "已支付", "支付了",
        "扣款成功", "扣款", "消费", "支出", "转账给", "成功付款", "付款", "交易成功"
    )
    private val screenPaymentWords = listOf(
        "支付", "交易", "转账", "收款", "退款", "消费", "扣款", "账单"
    )

    private val billNoiseWords = listOf(
        "本月账单", "月账单", "年度账单", "账单已出", "账单日", "还款提醒",
        "账单分期", "额度提醒", "账单汇总"
    )

    private val successScreenWords = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "交易成功",
        "转账成功", "收款成功", "已收款", "收款到账"
    )

    /**
     * Fields that appear on a payment result page ("支付成功" / "付款成功").
     * Such a page often has only the success title, amount, merchant and a
     * 完成 button, so it is accepted with one context field instead of the two
     * bill-detail markers required elsewhere.
     */
    private val successPageContext = listOf(
        "交易单号", "账单详情", "付款方式", "支付方式", "当前状态",
        "零钱", "花呗", "余额宝", "银行卡", "商户全称", "商户名称",
        "收款方", "商品说明", "订单号", "完成", "查看账单",
        "账单分类", "支付时间", "付款时间", "交易时间"
    )

    // A chat page can contain words like "支付成功" and an amount; skip it
    // before the success-page rule gets a chance to fire.
    private val chatNoiseWords = listOf(
        "按住 说话", "按住说话", "聊天信息", "语音通话", "视频通话"
    )

    private val screenIndicators = listOf(
        "交易单号", "账单详情", "付款方式", "支付方式", "当前状态",
        "交易成功", "支付成功", "转账成功", "已收款", "付款成功",
        "收款方", "交易时间", "创建时间", "支付时间", "付款时间",
        "商品说明", "订单号", "商户全称", "商户名称", "零钱",
        "花呗", "余额宝", "银行卡", "账单分类", "转账给"
    )

    fun fromNotification(pkg: String, title: String, body: String): ParsedPayment? =
        analyzeNotification(pkg, title, body).payment

    fun analyzeNotification(pkg: String, title: String, body: String): ParseResult {
        if (pkg !in supportedPackages) {
            return ParseResult(null, "不支持的来源应用")
        }
        val titleText = title.normalize()
        val bodyText = body.normalize()
        val combined = "$titleText $bodyText".trim()
        if (combined.length < 4) {
            return ParseResult(null, "通知文本太少")
        }
        if (billNoiseWords.any { combined.contains(it) }) {
            return ParseResult(null, "账单汇总/还款提醒，已忽略")
        }
        if (!hasNotificationContext(pkg, titleText, bodyText)) {
            return ParseResult(null, "不是支付类通知")
        }
        return parse(pkg, combined, requireScreenIndicators = false)
    }

    fun fromScreen(pkg: String, screenText: String): ParsedPayment? =
        analyzeScreen(pkg, screenText).payment

    fun analyzeScreen(pkg: String, screenText: String): ParseResult {
        if (pkg !in supportedPackages) {
            return ParseResult(null, "不支持的来源应用")
        }
        val combined = screenText.normalize()
        if (combined.length < 8) {
            return ParseResult(null, "页面文本太少")
        }
        if (billNoiseWords.any { combined.contains(it) }) {
            return ParseResult(null, "账单汇总/还款提醒，已忽略")
        }
        if (chatNoiseWords.any { combined.contains(it) }) {
            return ParseResult(null, "聊天界面，已忽略")
        }
        return parse(pkg, combined, requireScreenIndicators = true)
    }

    private fun parse(
        pkg: String,
        combined: String,
        requireScreenIndicators: Boolean
    ): ParseResult {
        val candidateWords = incomeWords + expenseWords +
            (if (requireScreenIndicators) screenPaymentWords else emptyList())
        val hasPaymentWord = candidateWords.any { combined.contains(it) } ||
            receivedAmount.containsMatchIn(combined)
        if (!hasPaymentWord) {
            return ParseResult(null, "未发现支付关键词")
        }

        if (requireScreenIndicators) {
            val isSuccessPage = successScreenWords.any { combined.contains(it) }
            if (isSuccessPage) {
                val contextMarkers = successPageContext.count { combined.contains(it) }
                if (contextMarkers < 1) {
                    return ParseResult(null, "支付成功页标记不足（已识别 $contextMarkers/1）")
                }
            } else {
                val indicators = screenIndicators.count { combined.contains(it) }
                if (indicators < 2) {
                    return ParseResult(null, "支付页面标记不足（已识别 $indicators/2）")
                }
            }
        }

        val type = detectType(combined)
            ?: if (requireScreenIndicators) {
                // Most single bill detail pages that only label "支付/交易"
                // without a direction word are outflows; users can edit it.
                PaymentType.EXPENSE
            } else {
                return ParseResult(null, "无法判断收支方向")
            }
        val amountCents = extractAmount(combined, allowLoose = requireScreenIndicators)
            ?: return ParseResult(null, "未找到金额")
        val merchant = extractMerchant(combined)
        val occurredAtMillis = if (requireScreenIndicators) {
            extractOccurredAt(combined)
        } else {
            null
        }
        return ParseResult(
            payment = ParsedPayment(
                type = type,
                amountCents = amountCents,
                merchant = merchant,
                rawText = combined.take(500),
                occurredAtMillis = occurredAtMillis
            ),
            reason = "识别成功"
        )
    }

    private fun hasNotificationContext(pkg: String, title: String, body: String): Boolean =
        when (pkg) {
            WECHAT_PACKAGE ->
                title.contains("微信支付") ||
                    body.contains("微信支付") ||
                    body.contains("交易单号") ||
                    body.contains("微信支付凭证") ||
                    title.contains("服务通知")
            ALIPAY_PACKAGE ->
                title.contains("支付宝") ||
                    body.contains("支付宝") ||
                    title.contains("支付助手") ||
                    body.contains("交易单号") ||
                    title.contains("交易提醒") ||
                    title.contains("支付提醒") ||
                    title.contains("服务提醒")
            else -> false
        }

    private fun detectType(text: String): PaymentType? {
        // An explicit +/- sign on the amount is the strongest signal.
        signedAmountType(text)?.let { return it }

        // "收款方", "付款方式" and "支付方式" are field labels that appear on
        // both income and expense pages. Strip them before keyword matching so
        // an income page is not mistaken for an expense because it says "付款方式".
        val neutral = text
            .replace("收款方", "")
            .replace("收款账户", "")
            .replace("收款机构", "")
            .replace("付款方", "")
            .replace("付款账户", "")
            .replace("付款方式", "")
            .replace("支付方式", "")
        if (incomeWords.any { neutral.contains(it) }) return PaymentType.INCOME
        if (receivedAmount.containsMatchIn(neutral)) return PaymentType.INCOME
        if (expenseWords.any { neutral.contains(it) }) return PaymentType.EXPENSE
        return null
    }

    // "微信支付收款0.50元" / "收款 ¥50.00" — but not "收款账户 张三".
    private val receivedAmount = Regex("""收款\s*[¥￥]?\s*[0-9]""")

    private val signedIncome = Regex(
        """(?:\+\s*[¥￥]\s*[0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|\+\s*[0-9]{1,7}(?:,[0-9]{3})*\.[0-9]{2}(?![0-9]))"""
    )
    private val signedExpense = Regex(
        """(?:[-−]\s*[¥￥]\s*[0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[-−]\s*[0-9]{1,7}(?:,[0-9]{3})*\.[0-9]{2}(?![0-9]))"""
    )

    private fun signedAmountType(text: String): PaymentType? {
        if (signedIncome.containsMatchIn(text)) return PaymentType.INCOME
        if (signedExpense.containsMatchIn(text)) return PaymentType.EXPENSE
        return null
    }

    private fun extractAmount(text: String, allowLoose: Boolean): Long? {
        if (allowLoose) {
            findCents(labeledAmount, text)?.let { return it }
            findCents(amountWithSymbol, text)?.let { return it }
            findCents(amountWithCurrencyCode, text)?.let { return it }
            findCents(standaloneAmount, text)?.let { return it }
            findCents(amountWithYuan, text)?.let { return it }
        } else {
            findCents(amountWithSymbol, text)?.let { return it }
            findCents(amountWithCurrencyCode, text)?.let { return it }
            findCents(amountWithYuan, text)?.let { return it }
            findCents(labeledAmount, text)?.let { return it }
        }
        return null
    }

    private fun findCents(regex: Regex, text: String): Long? {
        val raw = regex.find(text)?.groupValues?.getOrNull(1) ?: return null
        val cleaned = raw
            .replace(",", "")
            .trim()
            .trimStart('−', '-', '+')
        return yuanToCents(cleaned)
    }

    private fun yuanToCents(raw: String): Long? {
        if (raw.isBlank()) return null
        val parts = raw.split(".")
        val yuan = parts[0].toLongOrNull() ?: return null
        val cents = if (parts.size > 1) {
            (parts[1] + "00").take(2).toLongOrNull() ?: 0L
        } else {
            0L
        }
        val total = yuan * 100 + cents
        if (total <= 0 || total > 100_000_000L) return null
        return total
    }

    private fun extractMerchant(text: String): String? {
        val fromXiang = merchantAfterXiang.find(text)?.groupValues?.getOrNull(1)
        sanitizeMerchant(fromXiang)?.let { return it }
        val fromLabel = merchantAfterLabel.find(text)?.groupValues?.getOrNull(1)
        return sanitizeMerchant(fromLabel)
    }

    private fun sanitizeMerchant(value: String?): String? {
        val trimmed = value
            ?.trim()
            ?.trimEnd('的', '，', ',', '。')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        if (trimmed in merchantNoise) return null
        if (trimmed.length !in 2..24) return null
        if (trimmed.matches(Regex("""[¥￥]?[0-9][0-9,.\s]*元?"""))) return null
        if (trimmed.matches(Regex(""".*\d{4}[-/年].*"""))) return null
        return trimmed
    }

    private fun extractOccurredAt(text: String): Long? {
        for (label in paymentTimeLabels) {
            val escaped = Regex.escape(label)
            val withYear = Regex(
                """$escaped[:：]?\s*(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\s*(\d{1,2})[:：](\d{2})(?:[:：](\d{2}))?)?"""
            ).find(text)
            if (withYear != null) {
                runCatching {
                    val groups = withYear.groupValues
                    val date = LocalDate.of(
                        groups[1].toInt(),
                        groups[2].toInt(),
                        groups[3].toInt()
                    )
                    val time = LocalTime.of(
                        groups[4].toIntOrNull() ?: 0,
                        groups[5].toIntOrNull() ?: 0,
                        groups[6].toIntOrNull() ?: 0
                    )
                    return LocalDateTime.of(date, time)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }
            }

            val withoutYear = Regex(
                """$escaped[:：]?\s*(\d{1,2})[-/.月](\d{1,2})日?(?:\s*(\d{1,2})[:：](\d{2})(?:[:：](\d{2}))?)?"""
            ).find(text)
            if (withoutYear != null) {
                runCatching {
                    val groups = withoutYear.groupValues
                    val now = LocalDateTime.now()
                    var year = now.year
                    var date = LocalDate.of(year, groups[1].toInt(), groups[2].toInt())
                    if (date.isAfter(now.toLocalDate().plusDays(1))) {
                        year -= 1
                        date = LocalDate.of(year, groups[1].toInt(), groups[2].toInt())
                    }
                    val time = LocalTime.of(
                        groups[3].toIntOrNull() ?: 0,
                        groups[4].toIntOrNull() ?: 0,
                        groups[5].toIntOrNull() ?: 0
                    )
                    return LocalDateTime.of(date, time)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }
            }
        }
        return null
    }

    private fun String.normalize(): String =
        replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
