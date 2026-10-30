package com.qinghe.ledger.data

import com.qinghe.ledger.data.local.AutoRuleEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.model.TransactionType
import java.time.Instant
import java.time.ZoneId

/**
 * Merchant / product aware auto-categorizer.
 *
 * Priority:
 *  1. user and built-in keyword rules (longest keyword first)
 *  2. specific merchant keywords, then product/description keywords
 *  3. platform keywords (美团/淘宝/…) that only identify the parent category
 *  4. 餐饮 meal subcategory by payment hour
 *  5. the parent's 其他 child, or the top-level 其他
 *
 * Rule hits that only resolve to a parent are narrowed to a subcategory when
 * the merchant/product text (or the payment time) carries a signal.
 */
internal object AutoCategoryClassifier {

    data class Match(val category: CategoryEntity, val reason: String)

    private data class Hint(
        val keyword: String,
        val parent: String,
        val child: String? = null,
        val platform: Boolean = false
    )

    private val hints = listOf(
        // 餐饮
        Hint("早餐", "餐饮", "早餐"), Hint("早点", "餐饮", "早餐"),
        Hint("包子", "餐饮", "早餐"), Hint("豆浆", "餐饮", "早餐"),
        Hint("油条", "餐饮", "早餐"), Hint("煎饼", "餐饮", "早餐"),
        Hint("午餐", "餐饮", "午餐"), Hint("午饭", "餐饮", "午餐"),
        Hint("晚餐", "餐饮", "晚餐"), Hint("晚饭", "餐饮", "晚餐"),
        Hint("酒水", "餐饮", "酒水饮料"), Hint("饮料", "餐饮", "酒水饮料"),
        Hint("奶茶", "餐饮", "酒水饮料"), Hint("果汁", "餐饮", "酒水饮料"),
        Hint("可乐", "餐饮", "酒水饮料"), Hint("雪碧", "餐饮", "酒水饮料"),
        Hint("矿泉水", "餐饮", "酒水饮料"), Hint("咖啡", "餐饮", "酒水饮料"),
        Hint("瑞幸", "餐饮", "酒水饮料"), Hint("星巴克", "餐饮", "酒水饮料"),
        Hint("蜜雪冰城", "餐饮", "酒水饮料"), Hint("喜茶", "餐饮", "酒水饮料"),
        Hint("奈雪", "餐饮", "酒水饮料"), Hint("茶百道", "餐饮", "酒水饮料"),
        Hint("古茗", "餐饮", "酒水饮料"), Hint("沪上阿姨", "餐饮", "酒水饮料"),
        Hint("元气森林", "餐饮", "酒水饮料"), Hint("农夫山泉", "餐饮", "酒水饮料"),
        Hint("零食", "餐饮", "零食"), Hint("小吃", "餐饮", "零食"),
        Hint("薯片", "餐饮", "零食"), Hint("巧克力", "餐饮", "零食"),
        Hint("一卡通", "餐饮", "一卡通"), Hint("校园卡", "餐饮", "一卡通"),
        Hint("饭卡", "餐饮", "一卡通"),
        // 交通
        Hint("滴滴", "交通", "打车"), Hint("打车", "交通", "打车"),
        Hint("网约车", "交通", "打车"), Hint("出租", "交通", "打车"),
        Hint("高德打车", "交通", "打车"), Hint("花小猪", "交通", "打车"),
        Hint("T3出行", "交通", "打车"), Hint("曹操出行", "交通", "打车"),
        Hint("地铁", "交通", "公交地铁"), Hint("公交", "交通", "公交地铁"),
        Hint("轨道交通", "交通", "公交地铁"), Hint("乘车码", "交通", "公交地铁"),
        Hint("电动车", "交通", "电动车"), Hint("电瓶车", "交通", "电动车"),
        Hint("小电驴", "交通", "电动车"), Hint("充电桩", "交通", "电动车"),
        Hint("加油", "交通", "加油"), Hint("中石化", "交通", "加油"),
        Hint("中石油", "交通", "加油"), Hint("壳牌", "交通", "加油"),
        Hint("停车", "交通", "停车"), Hint("停车场", "交通", "停车"),
        Hint("高铁", "交通", "火车高铁"), Hint("火车", "交通", "火车高铁"),
        Hint("12306", "交通", "火车高铁"), Hint("铁路", "交通", "火车高铁"),
        // 购物
        Hint("超市", "购物", "日用"), Hint("便利店", "购物", "日用"),
        Hint("盒马", "购物", "日用"), Hint("山姆", "购物", "日用"),
        Hint("沃尔玛", "购物", "日用"), Hint("永辉", "购物", "日用"),
        Hint("日用品", "购物", "日用"), Hint("洗护", "购物", "日用"),
        Hint("纸巾", "购物", "日用"), Hint("牙膏", "购物", "日用"),
        Hint("优衣库", "购物", "服饰"), Hint("服饰", "购物", "服饰"),
        Hint("服装", "购物", "服饰"), Hint("衣服", "购物", "服饰"),
        Hint("鞋", "购物", "服饰"), Hint("袜子", "购物", "服饰"),
        Hint("内衣", "购物", "服饰"),
        Hint("数码", "购物", "数码"), Hint("手机", "购物", "数码"),
        Hint("电脑", "购物", "数码"), Hint("耳机", "购物", "数码"),
        Hint("相机", "购物", "数码"), Hint("联想", "购物", "数码"),
        Hint("戴尔", "购物", "数码"), Hint("小米之家", "购物", "数码"),
        Hint("Apple Store", "购物", "数码"),
        Hint("美妆", "购物", "美妆"), Hint("化妆品", "购物", "美妆"),
        Hint("护肤", "购物", "美妆"), Hint("口红", "购物", "美妆"),
        Hint("面膜", "购物", "美妆"), Hint("香水", "购物", "美妆"),
        // 居住
        Hint("房租", "居住", "房租"), Hint("租金", "居住", "房租"),
        Hint("房东", "居住", "房租"),
        Hint("水费", "居住", "水电"), Hint("电费", "居住", "水电"),
        Hint("燃气", "居住", "水电"), Hint("水电", "居住", "水电"),
        Hint("自来水", "居住", "水电"), Hint("国家电网", "居住", "水电"),
        Hint("物业", "居住", "物业"),
        Hint("宜家", "居住", "家居"), Hint("家居", "居住", "家居"),
        Hint("家具", "居住", "家居"), Hint("家装", "居住", "家居"),
        // 娱乐
        Hint("电影", "娱乐", "电影"), Hint("影院", "娱乐", "电影"),
        Hint("猫眼", "娱乐", "电影"), Hint("淘票票", "娱乐", "电影"),
        Hint("游戏", "娱乐", "游戏"), Hint("Steam", "娱乐", "游戏"),
        Hint("王者荣耀", "娱乐", "游戏"), Hint("原神", "娱乐", "游戏"),
        Hint("点券", "娱乐", "游戏"),
        Hint("旅行", "娱乐", "旅行"), Hint("酒店", "娱乐", "旅行"),
        Hint("民宿", "娱乐", "旅行"), Hint("机票", "娱乐", "旅行"),
        Hint("携程", "娱乐", "旅行"), Hint("飞猪", "娱乐", "旅行"),
        Hint("去哪儿", "娱乐", "旅行"),
        Hint("健身", "娱乐", "运动"), Hint("运动", "娱乐", "运动"),
        Hint("游泳", "娱乐", "运动"), Hint("球馆", "娱乐", "运动"),
        // 医疗
        Hint("药品", "医疗", "药品"), Hint("药房", "医疗", "药品"),
        Hint("药店", "医疗", "药品"), Hint("大药房", "医疗", "药品"),
        Hint("门诊", "医疗", "门诊"), Hint("挂号", "医疗", "门诊"),
        Hint("医院", "医疗", "门诊"), Hint("诊所", "医疗", "门诊"),
        Hint("卫生院", "医疗", "门诊"),
        Hint("体检", "医疗", "体检"),
        // 学习
        Hint("书店", "学习", "书籍"), Hint("图书", "学习", "书籍"),
        Hint("当当", "学习", "书籍"), Hint("买书", "学习", "书籍"),
        Hint("课程", "学习", "课程"), Hint("培训", "学习", "课程"),
        Hint("学费", "学习", "课程"), Hint("网课", "学习", "课程"),
        Hint("学而思", "学习", "课程"), Hint("新东方", "学习", "课程"),
        Hint("云服务", "学习", "云服务"), Hint("阿里云", "学习", "云服务"),
        Hint("腾讯云", "学习", "云服务"), Hint("百度网盘", "学习", "云服务"),
        Hint("iCloud", "学习", "云服务"), Hint("服务器", "学习", "云服务"),
        Hint("域名", "学习", "云服务"),
        Hint("文具", "学习", "文具"), Hint("办公用品", "学习", "文具"),
        Hint("打印", "学习", "文具"), Hint("复印", "学习", "文具"),
        // 人情
        Hint("红包", "人情", "红包"),
        Hint("礼物", "人情", "礼物"), Hint("礼品", "人情", "礼物"),
        Hint("花束", "人情", "礼物"),
        Hint("请客", "人情", "请客"), Hint("聚餐", "人情", "请客"),
        Hint("亲属卡", "人情", "亲属卡"),
        // 通讯
        Hint("话费", "通讯", "话费"), Hint("手机充值", "通讯", "话费"),
        Hint("中国移动", "通讯", "话费"), Hint("中国联通", "通讯", "话费"),
        Hint("中国电信", "通讯", "话费"), Hint("中国广电", "通讯", "话费"),
        Hint("流量", "通讯", "流量"), Hint("流量包", "通讯", "流量"),
        Hint("宽带", "通讯", "宽带"), Hint("光纤", "通讯", "宽带"),
        // 收入
        Hint("工资", "工资"), Hint("薪资", "工资"), Hint("月薪", "工资"),
        Hint("薪水", "工资"),
        Hint("奖金", "奖金"), Hint("绩效", "奖金"), Hint("年终奖", "奖金"),
        Hint("利息", "理财", "利息"), Hint("存款利息", "理财", "利息"),
        Hint("基金", "理财", "投资收益"), Hint("股票", "理财", "投资收益"),
        Hint("收益", "理财", "投资收益"), Hint("分红", "理财", "投资收益"),
        Hint("理财", "理财", "投资收益"),
        Hint("兼职", "兼职"), Hint("稿费", "兼职"), Hint("劳务", "兼职"),
        Hint("外快", "兼职"),
        Hint("压岁钱", "红包"), Hint("红包", "红包"),
        // 平台：只能确定大类，具体小类再按商品/时间细化
        Hint("美团", "餐饮", platform = true),
        Hint("美团外卖", "餐饮", platform = true),
        Hint("美团单车", "交通", platform = true),
        Hint("美团打车", "交通", platform = true),
        Hint("美团酒店", "娱乐", "旅行", platform = true),
        Hint("抖音", "购物", platform = true),
        Hint("抖音商城", "购物", platform = true),
        Hint("抖音支付", "购物", platform = true),
        Hint("抖音外卖", "餐饮", platform = true),
        Hint("抖音团购", "餐饮", platform = true),
        Hint("饿了么", "餐饮", platform = true),
        Hint("肯德基", "餐饮", platform = true),
        Hint("麦当劳", "餐饮", platform = true),
        Hint("汉堡王", "餐饮", platform = true),
        Hint("必胜客", "餐饮", platform = true),
        Hint("塔斯汀", "餐饮", platform = true),
        Hint("华莱士", "餐饮", platform = true),
        Hint("淘宝", "购物", platform = true),
        Hint("天猫", "购物", platform = true),
        Hint("京东", "购物", platform = true),
        Hint("拼多多", "购物", platform = true),
        Hint("唯品会", "购物", platform = true),
        Hint("苏宁", "购物", platform = true)
    )

    fun classify(
        type: TransactionType,
        merchant: String?,
        rawText: String,
        occurredAtMillis: Long,
        categories: List<CategoryEntity>,
        rules: List<AutoRuleEntity>
    ): Match? {
        val typeCategories = categories.filter { it.type == type.name }
        if (typeCategories.isEmpty()) return null
        val byId = typeCategories.associateBy { it.id }
        val topLevel = typeCategories.filter { it.parentId == null }
        val merchantText = merchant.orEmpty().trim()
        val productText = rawText.trim()
        val combined = "$merchantText $productText".trim()

        fun narrow(base: CategoryEntity): CategoryEntity {
            if (base.parentId != null) return base
            val children = typeCategories.filter { it.parentId == base.id }
            if (children.isEmpty()) return base
            val childHints = hints.filter { it.child != null && it.parent == base.name }
            bestHint(childHints, combined)?.let { hint ->
                children.firstOrNull { it.name == hint.child }?.let { return it }
            }
            children.firstOrNull { combined.contains(it.name, ignoreCase = true) }?.let {
                return it
            }
            if (base.name == "餐饮") {
                mealByHour(occurredAtMillis)?.let { meal ->
                    children.firstOrNull { it.name == meal }?.let { return it }
                }
            }
            return children.firstOrNull { it.name == "其他" } ?: base
        }

        fun matchHints(
            candidates: List<Hint>,
            text: String,
            label: String
        ): Match? {
            if (text.isBlank()) return null
            val applicable = candidates.filter { hint ->
                topLevel.any { it.name == hint.parent }
            }
            val hint = bestHint(applicable, text) ?: return null
            val parent = topLevel.firstOrNull { it.name == hint.parent } ?: return null
            val child = hint.child?.let { name ->
                typeCategories.firstOrNull {
                    it.parentId == parent.id && it.name == name
                }
            }
            return Match(child ?: narrow(parent), "$label:${hint.keyword}")
        }

        // 2. Learned and built-in rules: user rules first, then the longest keyword.
        rules.asSequence()
            .filter { it.keyword.isNotBlank() }
            .sortedWith(
                compareBy<AutoRuleEntity> { it.builtIn }
                    .thenByDescending { it.keyword.length }
            )
            .forEach { rule ->
                val keyword = rule.keyword.trim()
                val base = byId[rule.categoryId] ?: return@forEach
                if (!combined.contains(keyword, ignoreCase = true)) return@forEach
                return Match(narrow(base), "规则:$keyword")
            }

        val specific = hints.filter { !it.platform }
        val platforms = hints.filter { it.platform }
        matchHints(specific, merchantText, "商户")?.let { return it }
        matchHints(specific, productText, "商品")?.let { return it }
        matchHints(platforms, merchantText, "平台")?.let { return it }
        matchHints(platforms, productText, "平台")?.let { return it }

        val fallback = topLevel.firstOrNull { it.name == "其他" }
            ?: topLevel.firstOrNull()
            ?: return null
        return Match(narrow(fallback), "默认其他")
    }

    private fun bestHint(candidates: List<Hint>, text: String): Hint? =
        candidates.asSequence()
            .filter { text.contains(it.keyword, ignoreCase = true) }
            .maxByOrNull { it.keyword.length }

    private fun mealByHour(occurredAtMillis: Long): String? {
        val hour = Instant.ofEpochMilli(occurredAtMillis)
            .atZone(ZoneId.systemDefault())
            .hour
        return when (hour) {
            in 5..9 -> "早餐"
            in 10..14 -> "午餐"
            in 16..23 -> "晚餐"
            else -> null
        }
    }
}
