package com.qinghe.ledger.auto

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentParserTest {

    @Test
    fun `wechat payment notification is parsed as expense`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.WECHAT_PACKAGE,
            "微信支付",
            "向瑞幸咖啡付款¥18.00"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1800L, result.amountCents)
        assertEquals("瑞幸咖啡", result.merchant)
    }

    @Test
    fun `alipay payment notification with yuan suffix is parsed`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.ALIPAY_PACKAGE,
            "支付宝",
            "支付成功 12.34元"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1234L, result.amountCents)
    }

    @Test
    fun `alipay payment assistant notification is parsed`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.ALIPAY_PACKAGE,
            "支付助手",
            "你已成功付款 8.80元"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(880L, result.amountCents)
    }

    @Test
    fun `wechat money received is parsed as income`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.WECHAT_PACKAGE,
            "微信支付",
            "收款到账¥0.50"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(50L, result.amountCents)
    }

    @Test
    fun `ordinary chat message is ignored`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.WECHAT_PACKAGE,
            "张三",
            "我已经支付成功了，一共100元"
        )
        assertNull(result)
    }

    @Test
    fun `monthly bill summary is ignored`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.WECHAT_PACKAGE,
            "微信支付",
            "本月账单已出，共支出1234.56元"
        )
        assertNull(result)
    }

    @Test
    fun `alipay payment detail screen is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "支付成功 ¥25.00 收款方 杭州便利店 付款方式 余额宝 交易单号 2026102012345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(2500L, result.amountCents)
    }

    @Test
    fun `screen without enough payment markers is ignored`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "支付成功 ¥25.00 今天天气不错"
        )
        assertNull(result)
    }

    @Test
    fun `received transfer on screen is parsed as income`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "已收款 ¥200.00 转账成功 收款方 张三 交易单号 12345 当前状态 已到账"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(20000L, result.amountCents)
    }

    @Test
    fun `alipay bill detail with bare amount is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "交易成功 25.00 收款方 杭州便利店 付款方式 余额宝 交易单号 2026102012345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(2500L, result.amountCents)
        assertEquals("杭州便利店", result.merchant)
    }

    @Test
    fun `wechat bill detail with merchant full name is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "商户全称 瑞幸咖啡（北京）有限公司 当前状态 支付成功 支付方式 零钱 交易单号 4200001234 -¥18.00 支付时间 2026-10-20 14:35"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1800L, result.amountCents)
        assertEquals("瑞幸咖啡（北京）有限公司", result.merchant)
    }

    @Test
    fun `alipay income bill detail is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "交易成功 100.00 收款成功 收款方 张三 交易单号 12345 账单详情"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(10000L, result.amountCents)
        assertEquals("张三", result.merchant)
    }

    @Test
    fun `wechat bill page without success word still parses`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "当前状态 已完成 支付方式 零钱 交易单号 4200001234 ¥18.00 商户全称 瑞幸咖啡"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1800L, result.amountCents)
        assertEquals("瑞幸咖啡", result.merchant)
    }

    @Test
    fun `screen payment time is parsed from bill detail`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "账单详情 ¥25.80 当前状态 已完成 支付方式 零钱 交易单号 4200001234 商户全称 瑞幸咖啡 支付时间 2026-10-20 14:35"
        )
        requireNotNull(result)
        val expected = LocalDateTime.of(2026, 10, 20, 14, 35)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, result.occurredAtMillis)
    }

    @Test
    fun `page without merchant does not invent a merchant from ui text`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "返回 账单详情 ¥25.80 当前状态 已完成 支付方式 零钱 交易单号 4200001234 支付时间 2026-10-20 14:35"
        )
        requireNotNull(result)
        assertNull(result.merchant)
    }

    @Test
    fun `parse failure explains why the page was ignored`() {
        val result = PaymentParser.analyzeScreen(
            PaymentParser.WECHAT_PACKAGE,
            "支付成功 ¥25.00 今天天气不错"
        )
        assertNull(result.payment)
        assertEquals(true, result.reason.contains("标记不足"))
    }

    @Test
    fun `wechat payment success page is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "支付成功 ¥26.60 瑞幸咖啡 支付方式 零钱 完成"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(2660L, result.amountCents)
    }

    @Test
    fun `alipay payment success page is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "支付成功 ¥12.34 老王便利店 付款方式 余额宝 查看账单 完成"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1234L, result.amountCents)
    }

    @Test
    fun `success page with only a done button is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "支付成功 9.90 完成"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(990L, result.amountCents)
    }

    @Test
    fun `success words in a chat screen are ignored`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "张三 支付成功 ¥25.00 聊天信息 按住 说话 交易时间 14:35"
        )
        assertNull(result)
    }

    @Test
    fun `received transfer with payment method is parsed as income`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "转账成功 收款方 张三 付款方式 零钱 已到账 50.00 交易单号 12345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(5000L, result.amountCents)
    }

    @Test
    fun `signed income amount is parsed as income`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "账单详情 +100.00 收款方 张三 付款方式 余额宝 交易单号 2026102012345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(10000L, result.amountCents)
    }

    @Test
    fun `signed expense amount stays expense`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "账单详情 -18.00 商户全称 瑞幸咖啡 支付方式 零钱 交易单号 4200001234"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(1800L, result.amountCents)
    }

    @Test
    fun `alipay money received notification is income`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.ALIPAY_PACKAGE,
            "支付宝",
            "你已收到一笔钱 8.80元"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(880L, result.amountCents)
    }

    @Test
    fun `wechat collection notification is income`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.WECHAT_PACKAGE,
            "微信支付",
            "微信支付收款0.50元"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(50L, result.amountCents)
    }

    @Test
    fun `income page with payer label is income`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "转账成功 付款方 李四 收款账户 零钱 已到账 50.00 交易单号 12345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.INCOME, result.type)
        assertEquals(5000L, result.amountCents)
    }

    @Test
    fun `sent transfer page stays expense`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "转账成功 收款方 张三 付款账户 余额宝 50.00 交易单号 12345"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(5000L, result.amountCents)
    }

    @Test
    fun `comma separated amount is parsed`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.ALIPAY_PACKAGE,
            "支付宝",
            "支付成功 ¥1,234.50"
        )
        requireNotNull(result)
        assertEquals(123450L, result.amountCents)
    }
}
