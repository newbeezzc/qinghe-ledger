package com.qinghe.ledger.data

import com.qinghe.ledger.auto.PaymentType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickTextParserTest {

    private val nowMillis = LocalDateTime.of(2026, 11, 18, 21, 30)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun parse(text: String) = QuickTextParser.parse(text, nowMillis)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun `plain lunch amount`() {
        val parsed = parse("午饭 25")
        assertEquals(2500L, parsed.amountCents)
        assertEquals(PaymentType.EXPENSE, parsed.type)
        assertEquals(nowMillis, parsed.occurredAtMillis)
        assertEquals("午饭 25", parsed.note)
    }

    @Test
    fun `decimal taxi amount without a space`() {
        assertEquals(1850L, parse("打车18.5").amountCents)
    }

    @Test
    fun `yuan suffix is recognized`() {
        assertEquals(3200L, parse("买咖啡花了32元").amountCents)
    }

    @Test
    fun `yesterday sets the previous day at 15 00`() {
        val parsed = parse("昨天买奶茶12")
        assertEquals(1200L, parsed.amountCents)
        assertEquals(at(2026, 11, 17, 15, 0), parsed.occurredAtMillis)
    }

    @Test
    fun `day before yesterday sets two days ago`() {
        val parsed = parse("前天房租1200")
        assertEquals(120000L, parsed.amountCents)
        assertEquals(at(2026, 11, 16, 15, 0), parsed.occurredAtMillis)
    }

    @Test
    fun `explicit today keeps the current timestamp`() {
        assertEquals(nowMillis, parse("今天 奶茶 15").occurredAtMillis)
    }

    @Test
    fun `salary is income`() {
        val parsed = parse("工资 8000")
        assertEquals(800000L, parsed.amountCents)
        assertEquals(PaymentType.INCOME, parsed.type)
    }

    @Test
    fun `income label is income`() {
        assertEquals(PaymentType.INCOME, parse("收入 500").type)
    }

    @Test
    fun `received red packet is income`() {
        assertEquals(PaymentType.INCOME, parse("收到红包200").type)
    }

    @Test
    fun `sent red packet is expense`() {
        assertEquals(PaymentType.EXPENSE, parse("发红包200").type)
    }

    @Test
    fun `explicit plus sign is income`() {
        val parsed = parse("+25 午饭")
        assertEquals(2500L, parsed.amountCents)
        assertEquals(PaymentType.INCOME, parsed.type)
    }

    @Test
    fun `explicit minus sign is expense`() {
        val parsed = parse("-25 午饭")
        assertEquals(2500L, parsed.amountCents)
        assertEquals(PaymentType.EXPENSE, parsed.type)
    }

    @Test
    fun `chinese date digits are ignored`() {
        assertEquals(2500L, parse("2026年11月18日 午饭25").amountCents)
    }

    @Test
    fun `iso date digits are ignored`() {
        assertEquals(2500L, parse("2026-11-18 午饭25").amountCents)
    }

    @Test
    fun `clock time digits are ignored`() {
        assertEquals(1800L, parse("12:30 打车18").amountCents)
    }

    @Test
    fun `quantity digits are ignored`() {
        assertEquals(12000L, parse("3个人吃饭120").amountCents)
    }

    @Test
    fun `ordinal digits are ignored`() {
        assertEquals(2500L, parse("第2天 午饭25").amountCents)
    }

    @Test
    fun `order number is ignored`() {
        assertEquals(2500L, parse("订单号123456 支付25").amountCents)
    }

    @Test
    fun `discount amount is ignored`() {
        assertEquals(1800L, parse("优惠5元 打车18").amountCents)
    }

    @Test
    fun `percentage discount is ignored`() {
        assertEquals(12000L, parse("打8折 火锅120").amountCents)
    }

    @Test
    fun `promo only text has no amount`() {
        assertNull(parse("满100减20").amountCents)
    }

    @Test
    fun `phone number is ignored`() {
        assertEquals(5000L, parse("电话13800138000 充值50").amountCents)
    }

    @Test
    fun `duration digits are ignored`() {
        assertEquals(2000L, parse("停车10小时 20").amountCents)
    }

    @Test
    fun `zero amount is rejected`() {
        assertNull(parse("午饭 0").amountCents)
    }

    @Test
    fun `eight digit amount is rejected`() {
        assertNull(parse("午饭 99999999").amountCents)
    }

    @Test
    fun `full width digits are normalized`() {
        assertEquals(2500L, parse("午饭２５元").amountCents)
    }

    @Test
    fun `thousands separator is supported`() {
        assertEquals(120000L, parse("房租 1,200").amountCents)
    }

    @Test
    fun `currency symbol and cents are supported`() {
        assertEquals(2550L, parse("午饭¥25.50").amountCents)
    }

    @Test
    fun `kuai suffix is supported`() {
        assertEquals(1800L, parse("打车18块").amountCents)
    }

    @Test
    fun `text without digits has no amount`() {
        assertNull(parse("没有金额").amountCents)
        assertEquals("未找到金额，请加上数字，例如：午饭 25", parse("没有金额").reason)
    }
}
