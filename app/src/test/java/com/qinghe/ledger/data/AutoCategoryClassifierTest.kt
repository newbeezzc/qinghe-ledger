package com.qinghe.ledger.data

import com.qinghe.ledger.data.local.AutoRuleEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.model.TransactionType
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutoCategoryClassifierTest {

    private fun cat(
        id: Long,
        name: String,
        type: TransactionType = TransactionType.EXPENSE,
        parentId: Long? = null
    ) = CategoryEntity(
        id = id,
        name = name,
        emoji = "x",
        type = type.name,
        sortOrder = id.toInt(),
        parentId = parentId
    )

    private val categories = listOf(
        cat(1, "餐饮"), cat(2, "交通"), cat(3, "购物"), cat(4, "居住"), cat(5, "娱乐"),
        cat(6, "医疗"), cat(7, "学习"), cat(8, "人情"), cat(9, "通讯"), cat(10, "其他"),
        cat(101, "早餐", parentId = 1), cat(102, "午餐", parentId = 1),
        cat(103, "晚餐", parentId = 1), cat(104, "酒水饮料", parentId = 1),
        cat(105, "零食", parentId = 1), cat(106, "一卡通", parentId = 1),
        cat(107, "其他", parentId = 1),
        cat(201, "打车", parentId = 2), cat(202, "公交地铁", parentId = 2),
        cat(203, "其他", parentId = 2),
        cat(301, "日用", parentId = 3), cat(302, "服饰", parentId = 3),
        cat(303, "数码", parentId = 3), cat(304, "美妆", parentId = 3),
        cat(305, "其他", parentId = 3),
        cat(401, "房租", parentId = 4), cat(402, "水电", parentId = 4),
        cat(403, "其他", parentId = 4),
        cat(501, "电影", parentId = 5), cat(502, "游戏", parentId = 5),
        cat(503, "旅行", parentId = 5), cat(504, "运动", parentId = 5),
        cat(505, "其他", parentId = 5),
        cat(601, "药品", parentId = 6), cat(602, "门诊", parentId = 6),
        cat(603, "其他", parentId = 6),
        cat(701, "书籍", parentId = 7), cat(702, "课程", parentId = 7),
        cat(703, "云服务", parentId = 7), cat(704, "文具", parentId = 7),
        cat(705, "其他", parentId = 7),
        cat(801, "红包", parentId = 8), cat(802, "礼物", parentId = 8),
        cat(803, "请客", parentId = 8), cat(804, "其他", parentId = 8),
        cat(901, "话费", parentId = 9), cat(902, "流量", parentId = 9),
        cat(903, "宽带", parentId = 9), cat(904, "其他", parentId = 9),
        cat(11, "工资", TransactionType.INCOME), cat(12, "奖金", TransactionType.INCOME),
        cat(13, "理财", TransactionType.INCOME), cat(14, "兼职", TransactionType.INCOME),
        cat(15, "红包", TransactionType.INCOME), cat(16, "其他", TransactionType.INCOME),
        cat(1301, "利息", TransactionType.INCOME, 13),
        cat(1302, "投资收益", TransactionType.INCOME, 13),
        cat(1303, "其他", TransactionType.INCOME, 13)
    )

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, 26, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun classify(
        merchant: String?,
        rawText: String = "",
        type: TransactionType = TransactionType.EXPENSE,
        hour: Int = 12,
        rules: List<AutoRuleEntity> = emptyList()
    ) = AutoCategoryClassifier.classify(
        type = type,
        merchant = merchant,
        rawText = rawText,
        occurredAtMillis = at(hour),
        categories = categories,
        rules = rules
    )

    @Test
    fun `specific merchant is classified to a subcategory`() {
        val match = classify("瑞幸咖啡")
        assertEquals("酒水饮料", match?.category?.name)
    }

    @Test
    fun `product beats a generic platform merchant`() {
        val match = classify("美团外卖", "商品说明 星巴克拿铁 支付成功 ¥28.00")
        assertEquals("酒水饮料", match?.category?.name)
    }

    @Test
    fun `platform merchant falls back to the meal time`() {
        val match = classify("美团外卖", "黄焖鸡米饭", hour = 12)
        assertEquals("午餐", match?.category?.name)
    }

    @Test
    fun `transport merchant maps to a transport subcategory`() {
        val match = classify("滴滴出行")
        assertEquals("打车", match?.category?.name)
    }

    @Test
    fun `product keyword alone picks a subcategory`() {
        val match = classify("某某商店", "电影票 支付成功 ¥45.00")
        assertEquals("电影", match?.category?.name)
    }

    @Test
    fun `user rule has priority over builtin keywords`() {
        val match = classify(
            "瑞幸咖啡",
            rules = listOf(AutoRuleEntity(keyword = "瑞幸", categoryId = 101))
        )
        assertEquals("早餐", match?.category?.name)
    }

    @Test
    fun `rule pointing at a parent is narrowed by the product text`() {
        val match = classify(
            "星巴克",
            rules = listOf(AutoRuleEntity(keyword = "星巴克", categoryId = 1))
        )
        assertEquals("酒水饮料", match?.category?.name)
    }

    @Test
    fun `income merchant maps to an income category`() {
        val match = classify("工资", type = TransactionType.INCOME)
        assertEquals("工资", match?.category?.name)
    }

    @Test
    fun `restaurant rule is refined by the payment hour`() {
        val rules = listOf(AutoRuleEntity(keyword = "餐厅", categoryId = 1))
        assertEquals("早餐", classify("某某餐厅", hour = 8, rules = rules)?.category?.name)
        assertEquals("晚餐", classify("某某餐厅", hour = 19, rules = rules)?.category?.name)
    }

    @Test
    fun `user rule wins over a builtin rule with the same length`() {
        val rules = listOf(
            AutoRuleEntity(id = 1, keyword = "咖啡", categoryId = 1, builtIn = true),
            AutoRuleEntity(id = 2, keyword = "瑞幸", categoryId = 101, builtIn = false)
        )
        assertEquals("早餐", classify("瑞幸咖啡", rules = rules)?.category?.name)
    }

    @Test
    fun `longest product keyword wins`() {
        val match = classify("支付宝", "手机充值 支付成功 ¥50.00")
        assertEquals("话费", match?.category?.name)
    }

    @Test
    fun `douyin mall falls back to shopping`() {
        val match = classify("抖音商城")
        assertEquals(3L, match?.category?.parentId)
    }

    @Test
    fun `meituan bike falls back to transport`() {
        val match = classify("美团单车")
        assertEquals(2L, match?.category?.parentId)
    }

    @Test
    fun `douyin product keyword refines shopping category`() {
        val match = classify("抖音商城", "商品说明 电影票 支付成功 ¥45.00")
        assertEquals("电影", match?.category?.name)
    }

    @Test
    fun `unknown merchant falls back to top level other`() {
        val match = classify("某某商户", "支付成功 ¥10.00", hour = 15)
        assertNotNull(match)
        assertEquals("其他", match?.category?.name)
        assertEquals(null, match?.category?.parentId)
    }
}
