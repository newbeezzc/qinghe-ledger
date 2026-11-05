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

    @Test
    fun `douyin payment notification is parsed`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.DOUYIN_PACKAGE,
            "抖音支付",
            "支付成功 ¥68.00"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(6800L, result.amountCents)
    }

    @Test
    fun `meituan takeout notification is parsed`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.MEITUAN_TAKEOUT_PACKAGE,
            "美团外卖",
            "订单支付成功，共¥25.80"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(2580L, result.amountCents)
    }

    @Test
    fun `douyin chat notification is ignored`() {
        val result = PaymentParser.fromNotification(
            PaymentParser.DOUYIN_PACKAGE,
            "张三",
            "我已经支付成功100元了"
        )
        assertNull(result)
    }

    @Test
    fun `meituan success page is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.MEITUAN_PACKAGE,
            "支付成功 ¥25.80 订单号 123456 支付方式 美团支付 完成"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(2580L, result.amountCents)
    }

    @Test
    fun `douyin success page is parsed`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.DOUYIN_PACKAGE,
            "支付成功 ¥68.00 订单号 123456 支付方式 抖音支付 完成"
        )
        requireNotNull(result)
        assertEquals(PaymentType.EXPENSE, result.type)
        assertEquals(6800L, result.amountCents)
    }

    @Test
    fun `alipay home page with balances is not recorded`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            """
            支付宝
            扫一扫
            收付款
            出行
            卡包
            花呗 可用额度 ¥2,500.00
            余额宝 昨日收益 ¥0.52
            银行卡
            首页 理财 生活 消息 我的
            """.trimIndent()
        )
        assertNull(result)
    }

    @Test
    fun `alipay profile page with worth and limits is not recorded`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            """
            支付宝 我的
            总资产 ¥12,345.67
            花呗 可用额度 ¥2,500.00
            余额宝 ¥1,234.56
            银行卡 2 张
            首页 理财 生活 消息 我的
            """.trimIndent()
        )
        assertNull(result)
    }

    @Test
    fun `alipay balance page with a number is not recorded`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "余额宝 七日年化 2.35% 昨日收益 0.52 元 转入 转出 支付宝"
        )
        assertNull(result)
    }

    @Test
    fun `alipay message list with success cards is not recorded`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            """
            支付成功 ¥12.34 付款方式 余额宝
            支付成功 ¥56.78 付款方式 花呗
            消息
            """.trimIndent()
        )
        assertNull(result)
    }

    @Test
    fun `unpaid order detail is not recorded`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "订单详情 订单号 20261105001 当前状态 待付款 订单金额 ¥99.00 支付方式 花呗"
        )
        assertNull(result)
    }

    @Test
    fun `bill list page with several amounts is ignored`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "¥12.34 交易成功\n¥56.78 交易成功\n¥90.12 交易成功"
        )
        assertNull(result)
    }

    @Test
    fun `bill detail prefers the paid amount over the original amount`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.WECHAT_PACKAGE,
            "账单详情 订单金额 ¥100.00 优惠金额 ¥5.00 实付金额 ¥95.00 当前状态 已完成 交易单号 4200001234 支付时间 2026-11-05 10:00"
        )
        requireNotNull(result)
        assertEquals(9500L, result.amountCents)
    }

    @Test
    fun `bill detail with order and trade number is not treated as a list`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "账单详情 订单号 20261105001 交易单号 202611050011234 订单金额 ¥95.00 当前状态 交易成功"
        )
        requireNotNull(result)
        assertEquals(9500L, result.amountCents)
    }

    @Test
    fun `success page prefers the paid amount when a discount comes first`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "支付成功 优惠 ¥1.00 实付 ¥12.34 收款方 老王便利店"
        )
        requireNotNull(result)
        assertEquals(1234L, result.amountCents)
    }

    @Test
    fun `credit card repayment page is ignored`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "信用卡账单详情 本期账单 账单金额 ¥1,234.56 最低还款 ¥123.46 支付方式 自动还款 当前状态 待还款"
        )
        assertNull(result)
    }

    @Test
    fun `success page may mention the next repayment`() {
        val result = PaymentParser.fromScreen(
            PaymentParser.ALIPAY_PACKAGE,
            "支付成功 ¥12.34 收款方 老王便利店 付款方式 花呗 下月应还 ¥12.34 完成"
        )
        requireNotNull(result)
        assertEquals(1234L, result.amountCents)
    }
}
