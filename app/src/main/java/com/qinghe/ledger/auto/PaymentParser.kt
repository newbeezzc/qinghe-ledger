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
    const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
    const val DOUYIN_LITE_PACKAGE = "com.ss.android.ugc.aweme.lite"
    const val MEITUAN_PACKAGE = "com.sankuai.meituan"
    const val MEITUAN_TAKEOUT_PACKAGE = "com.sankuai.meituan.takeoutnew"

    val douyinPackages = setOf(DOUYIN_PACKAGE, DOUYIN_LITE_PACKAGE)
    val meituanPackages = setOf(MEITUAN_PACKAGE, MEITUAN_TAKEOUT_PACKAGE)

    val supportedPackages = setOf(WECHAT_PACKAGE, ALIPAY_PACKAGE) +
        douyinPackages + meituanPackages

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
        Regex("""(?:收款方|商户全称|商户名称|交易对方|付款给|转账给|商家|店铺|商品名称|商户|对方)[:：]?\s*([^\s，,。；;]{1,24})""")

    private val merchantNoise = setOf(
        "返回", "账单详情", "账单", "详情", "更多", "复制", "投诉", "联系客服",
        "微信支付", "支付宝", "抖音", "抖音支付", "抖音商城", "美团", "美团支付", "美团外卖",
        "已完成", "支付成功", "交易成功", "当前状态",
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
    private val billNoiseWords = listOf(
        "本月账单", "月账单", "年度账单", "账单已出", "账单日", "还款提醒",
        "账单分期", "额度提醒", "账单汇总"
    )

    /**
     * Credit-card / repayment wording. Checked only for bill-detail candidates:
     * a real payment result page may legitimately mention "下月应还" after a
     * 花呗 payment, but a repayment page must never be recorded as a purchase.
     */
    private val repaymentWords = listOf(
        "本期账单", "账单金额", "最低还款", "还款日", "应还", "待还"
    )

    private val successScreenWords = listOf(
        "支付成功", "付款成功", "支付完成", "付款完成", "交易成功",
        "转账成功", "收款成功", "已收款", "收款到账"
    )

    private val billDetailTitles = listOf(
        "账单详情", "交易详情", "转账详情", "订单详情", "付款详情"
    )

    private val billDetailFields = listOf(
        "交易单号", "订单号", "当前状态", "已完成", "交易成功", "支付成功",
        "付款成功", "收款成功", "付款方式", "支付方式", "收款方",
        "商户全称", "商户名称", "商品说明", "账单分类"
    ) + paymentTimeLabels

    private val detailStatusWords = listOf(
        "当前状态", "已完成", "交易成功", "支付成功", "付款成功",
        "收款成功", "已收款", "已到账"
    )

    /**
     * A payment result page ("支付成功" / "付款成功") needs one *strong* page
     * marker on top of the success title. Payment-method labels such as
     * 付款方式 / 花呗 / 余额宝 are deliberately absent: they are ordinary
     * navigation text in Alipay and must never count as evidence.
     */
    private val successPageContext = listOf(
        "交易单号", "账单详情", "当前状态", "商户全称", "商户名称",
        "收款方", "商品说明", "订单号", "完成", "查看账单",
        "账单分类", "支付时间", "付款时间", "交易时间",
        "返回商家", "我知道了"
    )

    // Orders that were never paid, or were closed/cancelled, are not expenses.
    private val closedOrderWords = listOf(
        "待付款", "等待付款", "未付款", "未支付", "交易关闭", "订单关闭",
        "已取消", "已关闭"
    )

    /**
     * Discount / promotion wording. When a payment page shows both the
     * pre-discount total and the discount, the amount that was actually paid is
     * the total minus the discount — recording the pre-discount total is wrong.
     */
    private val discountLabels = listOf(
        "优惠金额", "优惠", "立减", "满减", "减免", "折扣", "抵扣", "已减",
        "优惠券", "红包抵扣"
    )
    private val paidAmountLabels = listOf(
        "实付金额", "实付款", "实付", "实收金额", "实收款", "实收",
        "支付金额", "付款金额", "交易金额"
    )
    private val preDiscountAmountLabels = listOf(
        "订单金额", "商品金额", "商品总价", "订单总价", "原价", "总价", "合计金额"
    )

    /** Discount label immediately followed by an amount, e.g. "优惠 ¥5.00". */
    private fun isDiscountAmount(line: String): Boolean = discountLabels.any { label ->
        Regex("""${Regex.escape(label)}[\s:：¥￥−-]*[0-9]""").containsMatchIn(line)
    }

    /**
     * Amounts that belong to asset/balance widgets rather than to a payment.
     * Matched as label + amount so a merchant called "优惠超市" is not skipped.
     */
    private val assetAmountLine = Regex(
        """(?:总资产|可用额度|额度|余额|收益|积分|能量|冻结|待还|应还|还款|红包|累计|转入|转出|理财|基金|黄金|保险|信用|借呗|备用金|分期)(?:金额|额度)?[\s:：¥￥]*[0-9]"""
    )

    private val paymentLineWords = successScreenWords + billDetailTitles + listOf(
        "交易单号", "订单号", "交易金额", "订单金额", "支付金额",
        "付款金额", "实付金额", "退款金额", "合计", "付款方式", "支付方式"
    )

    private val screenLabeledAmounts = listOf(
        "实付金额", "实付款", "实付", "实收金额", "实收款", "实收",
        "支付金额", "付款金额", "交易金额", "订单金额", "退款金额", "合计"
    ).map { label ->
        Regex(
            """${Regex.escape(label)}[:：]?\s*[¥￥]?\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)"""
        )
    }
    private val genericAmountLabel = Regex(
        """(?<![惠减扣折让])金额[:：]?\s*[¥￥]?\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)"""
    )

    private val tradeNoValue = Regex("""交易单号[:：]?\s*([A-Za-z0-9]{4,})""")
    private val orderNoValue = Regex("""订单号[:：]?\s*([A-Za-z0-9]{4,})""")

    // A chat page can contain words like "支付成功" and an amount; skip it
    // before the success-page rule gets a chance to fire.
    private val chatNoiseWords = listOf(
        "按住 说话", "按住说话", "聊天信息", "语音通话", "视频通话"
    )

    private enum class ScreenPage { SUCCESS, BILL_DETAIL }

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
        return parseNotification(combined)
    }

    fun fromScreen(pkg: String, screenText: String): ParsedPayment? =
        analyzeScreen(pkg, screenText).payment

    /**
     * Screen capture is intentionally strict: a page must look like a payment
     * result page or a single bill detail page *before* any amount is read.
     * Counting generic words (支付 / 交易 / 花呗 / 余额宝 …) is not enough —
     * those appear all over ordinary Alipay screens.
     */
    fun analyzeScreen(pkg: String, screenText: String): ParseResult {
        if (pkg !in supportedPackages) {
            return ParseResult(null, "不支持的来源应用")
        }
        val lines = screenText.lineSequence()
            .map { it.normalize() }
            .filter { it.isNotEmpty() }
            .toList()
        val combined = lines.joinToString(" ")
        if (combined.length < 8) {
            return ParseResult(null, "页面文本太少")
        }
        if (billNoiseWords.any { combined.contains(it) }) {
            return ParseResult(null, "账单汇总/还款提醒，已忽略")
        }
        if (chatNoiseWords.any { combined.contains(it) }) {
            return ParseResult(null, "聊天界面，已忽略")
        }
        if (closedOrderWords.any { combined.contains(it) }) {
            return ParseResult(null, "订单未支付/已关闭，已忽略")
        }

        val hasDetailTitle = billDetailTitles.any { combined.contains(it) }
        // Several distinct amounts on one page = a bill/order list, not one
        // payment. Discount lines are excluded: a success page legitimately
        // shows the original amount + discount + paid amount, which is still
        // one payment. Distinct values keep duplicated accessibility nodes from
        // inflating the count on a real detail page.
        val distinctAmounts = lines.asSequence()
            .filterNot { isDiscountAmount(it) }
            .flatMap { line -> amountWithSymbol.findAll(line).map { it.groupValues[1] } }
            .distinct()
            .count()
        if (distinctAmounts >= 3 && !hasDetailTitle) {
            return ParseResult(null, "疑似账单列表页，已忽略")
        }
        val distinctOrderNos = orderNoValue.findAll(combined)
            .map { it.groupValues[1] }
            .distinct()
            .count()
        val distinctTradeNos = tradeNoValue.findAll(combined)
            .map { it.groupValues[1] }
            .distinct()
            .count()
        if (distinctOrderNos >= 2 || distinctTradeNos >= 2) {
            return ParseResult(null, "疑似多笔账单列表页，已忽略")
        }

        val hasSuccessWord = successScreenWords.any { combined.contains(it) }
        val page = when {
            hasDetailTitle &&
                billDetailFields.count { combined.contains(it) } >= 2 ->
                ScreenPage.BILL_DETAIL

            (combined.contains("交易单号") || combined.contains("订单号")) &&
                billDetailFields.count { combined.contains(it) } >= 3 &&
                detailStatusWords.any { combined.contains(it) } ->
                ScreenPage.BILL_DETAIL

            hasSuccessWord &&
                successPageContext.any { combined.contains(it) } ->
                ScreenPage.SUCCESS

            hasSuccessWord ->
                // Diagnostics and docs refer to this reason; keep the wording.
                return ParseResult(null, "支付成功页标记不足（未发现完成/交易单号等上下文）")

            else -> return ParseResult(null, "不是支付结果/账单详情页，已忽略")
        }

        if (page == ScreenPage.BILL_DETAIL &&
            repaymentWords.any { combined.contains(it) }
        ) {
            return ParseResult(null, "账单汇总/还款提醒，已忽略")
        }

        val amountCents = extractScreenAmount(combined, lines)
            ?: return ParseResult(null, "未找到金额")
        val type = detectType(combined) ?: PaymentType.EXPENSE
        val merchant = extractMerchant(combined)
        return ParseResult(
            payment = ParsedPayment(
                type = type,
                amountCents = amountCents,
                merchant = merchant,
                rawText = combined.take(500),
                occurredAtMillis = extractOccurredAt(combined)
            ),
            reason = when (page) {
                ScreenPage.BILL_DETAIL -> "识别成功（账单详情页）"
                ScreenPage.SUCCESS -> "识别成功（支付成功页）"
            }
        )
    }

    private fun parseNotification(combined: String): ParseResult {
        val hasPaymentWord = incomeWords.any { combined.contains(it) } ||
            expenseWords.any { combined.contains(it) } ||
            receivedAmount.containsMatchIn(combined)
        if (!hasPaymentWord) {
            return ParseResult(null, "未发现支付关键词")
        }

        val type = detectType(combined)
            ?: return ParseResult(null, "无法判断收支方向")
        val amountCents = extractAmount(combined)
            ?: return ParseResult(null, "未找到金额")
        val merchant = extractMerchant(combined)
        return ParseResult(
            payment = ParsedPayment(
                type = type,
                amountCents = amountCents,
                merchant = merchant,
                rawText = combined.take(500),
                occurredAtMillis = null
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
            in douyinPackages ->
                title.contains("抖音") ||
                    body.contains("抖音") ||
                    body.contains("交易单号") ||
                    title.contains("订单支付") ||
                    body.contains("订单支付")
            in meituanPackages ->
                title.contains("美团") ||
                    body.contains("美团") ||
                    body.contains("交易单号") ||
                    title.contains("订单支付") ||
                    body.contains("订单支付")
            else -> false
        }

    private fun detectType(text: String): PaymentType? {
        // An explicit +/- sign on the amount is the strongest signal.
        signedAmountType(text)?.let { return it }

        // Strip brand words and navigation entries first: "支付宝" contains
        // "支付", "收付款"/"付款码" contain "付款", and "花呗"/"余额宝" are
        // ordinary home-page text. Without this, every Alipay screen looks
        // like an expense.
        // "收款方", "付款方式" and "支付方式" are field labels that appear on
        // both income and expense pages, so they are stripped as well.
        val neutral = text
            .replace("支付宝", "")
            .replace("微信支付", "")
            .replace("抖音支付", "")
            .replace("美团支付", "")
            .replace("收付款", "")
            .replace("付款码", "")
            .replace("收款码", "")
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

    private fun extractAmount(text: String): Long? {
        extractDiscountedAmount(text)?.let { return it }
        findCents(amountWithSymbol, text)?.let { return it }
        findCents(amountWithCurrencyCode, text)?.let { return it }
        findCents(amountWithYuan, text)?.let { return it }
        findCents(labeledAmount, text)?.let { return it }
        return null
    }

    /**
     * Discounted payments show the pre-discount total and the discount. The
     * paid amount is the difference, so never record the pre-discount total.
     * An explicit 实付 / 支付金额 label always wins.
     */
    private fun extractDiscountedAmount(text: String): Long? {
        if (!hasDiscountAmount(text)) return null
        labeledCents(text, paidAmountLabels)?.let { return it }
        val preDiscount = labeledCents(text, preDiscountAmountLabels)
        val discount = labeledCents(text, discountLabels)
        if (preDiscount != null && discount != null && preDiscount > discount) {
            return preDiscount - discount
        }
        // No explicit labels: take the first amount that is not itself a
        // discount, e.g. "支付成功 ¥95.00 优惠 ¥5.00".
        for (regex in listOf(
            amountWithSymbol, amountWithCurrencyCode, amountWithYuan, standaloneAmount
        )) {
            for (match in regex.findAll(text)) {
                val prefix = text.substring(maxOf(0, match.range.first - 12), match.range.first)
                if (discountLabels.any { prefix.contains(it) }) continue
                centsOf(match.groupValues[1])?.let { return it }
            }
        }
        return null
    }

    /**
     * Screen amounts are read only after the page shape has been accepted.
     * Explicit labels win; bare currency amounts are skipped when the line
     * describes a balance, credit limit, discount or reward.
     */
    private fun extractScreenAmount(text: String, lines: List<String>): Long? {
        extractDiscountedScreenAmount(text, lines)?.let { return it }
        for (regex in screenLabeledAmounts) {
            findCents(regex, text)?.let { return it }
        }
        findCents(genericAmountLabel, text)?.let { return it }
        findCentsOnPaymentLines(amountWithSymbol, lines)?.let { return it }
        findCentsOnPaymentLines(amountWithCurrencyCode, lines)?.let { return it }
        findCentsOnPaymentLines(standaloneAmount, lines)?.let { return it }
        findCentsOnPaymentLines(amountWithYuan, lines)?.let { return it }
        return null
    }

    /**
     * Prefer the paid amount on discounted bill / success pages:
     * 1. an explicit 实付 / 支付金额 label;
     * 2. the pre-discount total minus the discount (or the displayed net);
     * 3. otherwise the first amount that is not a discount.
     */
    private fun extractDiscountedScreenAmount(text: String, lines: List<String>): Long? {
        if (!hasDiscountAmount(text)) return null
        labeledCents(text, paidAmountLabels)?.let { return it }
        val preDiscount = labeledCents(text, preDiscountAmountLabels)
        val discount = labeledCents(text, discountLabels)
        val candidates = collectAmountCandidates(lines)
        if (preDiscount != null && discount != null && preDiscount > discount) {
            val net = preDiscount - discount
            if (candidates.any { it == net }) return net
            candidates.firstOrNull { it != preDiscount && it != discount }?.let { return it }
            return net
        }
        return candidates.firstOrNull()
    }

    private fun hasDiscountAmount(text: String): Boolean = discountLabels.any { label ->
        Regex("""${Regex.escape(label)}[\s:：¥￥−-]*[0-9]""").containsMatchIn(text)
    }

    private fun labeledCents(text: String, labels: List<String>): Long? {
        for (label in labels) {
            val regex = Regex(
                """${Regex.escape(label)}[:：]?\s*[¥￥]?\s*[-−]?\s*[¥￥]?\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)"""
            )
            findCents(regex, text)?.let { return it }
        }
        return null
    }

    private fun collectAmountCandidates(lines: List<String>): List<Long> {
        val found = mutableListOf<Long>()
        for (line in lines) {
            if (isDiscountAmount(line)) continue
            if (looksLikeAsset(line)) continue
            for (regex in listOf(
                amountWithSymbol, amountWithCurrencyCode, standaloneAmount, amountWithYuan
            )) {
                found.addAll(findAllCents(regex, line))
            }
        }
        return found.distinct()
    }

    private fun findCentsOnPaymentLines(regex: Regex, lines: List<String>): Long? {
        for (line in lines) {
            if (looksLikeAsset(line)) continue
            findCents(regex, line)?.let { return it }
        }
        return null
    }

    private fun looksLikeAsset(line: String): Boolean =
        paymentLineWords.none { line.contains(it) } && assetAmountLine.containsMatchIn(line)

    private fun findCents(regex: Regex, text: String): Long? =
        findAllCents(regex, text).firstOrNull()

    private fun findAllCents(regex: Regex, text: String): List<Long> =
        regex.findAll(text).mapNotNull { match ->
            val raw = match.groupValues.getOrNull(1) ?: return@mapNotNull null
            centsOf(raw)
        }.toList()

    private fun centsOf(raw: String): Long? {
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
