package com.qinghe.ledger.data

import com.qinghe.ledger.auto.ParsedPayment
import com.qinghe.ledger.auto.PaymentType
import com.qinghe.ledger.data.local.AccountDelta
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.AppDatabase
import com.qinghe.ledger.data.local.AutoRuleEntity
import com.qinghe.ledger.data.local.BudgetEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.AccountType
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutoRecordResult(
    val transactionId: Long,
    val amountCents: Long,
    val type: PaymentType,
    val categoryName: String,
    val accountName: String,
    val occurredAt: Long,
    val note: String = ""
)

class LedgerRepository(private val db: AppDatabase) {
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val transactionDao = db.transactionDao()
    private val budgetDao = db.budgetDao()
    private val ruleDao = db.autoRuleDao()

    private val seedMutex = Mutex()

    val accounts: Flow<List<AccountEntity>> = accountDao.observeAll()
    val categories: Flow<List<CategoryEntity>> = categoryDao.observeAll()
    val rules: Flow<List<AutoRuleEntity>> = ruleDao.observeAll()
    val accountDeltas: Flow<List<AccountDelta>> = accountDao.observeDeltas()
    val latestAutoTransaction: Flow<TransactionEntity?> = transactionDao.observeLatestAuto()

    fun transactionsBetween(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>> =
        transactionDao.observeBetween(startMillis, endMillis)

    fun observeBudget(month: String): Flow<BudgetEntity?> = budgetDao.observe(month)

    suspend fun seedDefaults() = seedMutex.withLock {
        if (accountDao.count() == 0) {
            defaultAccounts().forEachIndexed { index, account ->
                accountDao.insert(account.copy(sortOrder = index))
            }
        }
        seedCategories()
        if (ruleDao.count() == 0) {
            val categories = categoryDao.getAll()
            fun categoryId(name: String, type: TransactionType): Long? =
                categories.firstOrNull {
                    it.name == name && it.type == type.name && it.parentId == null
                }?.id

            defaultRules().forEach { (keyword, categoryName, type) ->
                val id = categoryId(categoryName, type) ?: return@forEach
                ruleDao.insert(
                    AutoRuleEntity(keyword = keyword, categoryId = id, builtIn = true)
                )
            }
        }
    }

    private suspend fun seedCategories() {
        val seeds = defaultCategorySeeds()
        var existing = categoryDao.getAll()
        if (existing.none { it.parentId == null }) {
            seeds.filter { it.parent == null }.forEachIndexed { index, seed ->
                categoryDao.insert(
                    CategoryEntity(
                        name = seed.name,
                        emoji = seed.emoji,
                        type = seed.type.name,
                        colorHex = seed.colorHex,
                        sortOrder = index,
                        builtIn = true
                    )
                )
            }
            existing = categoryDao.getAll()
        }

        val parents = existing.filter { it.parentId == null }
            .associateBy { it.name to it.type }
        seeds.filter { it.parent != null }.forEach { seed ->
            val parent = parents[seed.parent to seed.type.name] ?: return@forEach
            val siblings = existing.filter { it.parentId == parent.id }
            if (siblings.any { it.name == seed.name }) return@forEach
            categoryDao.insert(
                CategoryEntity(
                    name = seed.name,
                    emoji = seed.emoji,
                    type = seed.type.name,
                    colorHex = seed.colorHex,
                    sortOrder = siblings.size,
                    builtIn = true,
                    parentId = parent.id
                )
            )
        }
    }

    suspend fun addTransaction(transaction: TransactionEntity): Long =
        transactionDao.insert(transaction)

    suspend fun updateTransaction(transaction: TransactionEntity) =
        transactionDao.update(transaction)

    suspend fun deleteTransaction(transaction: TransactionEntity) =
        transactionDao.delete(transaction)

    suspend fun findTransaction(id: Long): TransactionEntity? =
        transactionDao.findById(id)

    suspend fun addAccount(name: String, type: AccountType, initialBalanceCents: Long): Long {
        val existing = accountDao.getAll()
        return accountDao.insert(
            AccountEntity(
                name = name.trim(),
                type = type.name,
                initialBalanceCents = initialBalanceCents,
                sortOrder = existing.size
            )
        )
    }

    suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)

    suspend fun deleteAccount(account: AccountEntity): Boolean {
        if (transactionDao.countForAccount(account.id) > 0) return false
        accountDao.delete(account)
        return true
    }

    suspend fun addCategory(
        name: String,
        emoji: String,
        type: TransactionType,
        parentId: Long? = null
    ): Long {
        val all = categoryDao.getAll()
        val parent = parentId?.let { id ->
            all.firstOrNull { it.id == id && it.type == type.name }
        }
        val topLevelCount = all.count { it.type == type.name && it.parentId == null }
        val color = parent?.colorHex
            ?: categoryPalette[topLevelCount % categoryPalette.size]
        val sortOrder = if (parent != null) {
            all.count { it.parentId == parent.id }
        } else {
            topLevelCount
        }
        return categoryDao.insert(
            CategoryEntity(
                name = name.trim(),
                emoji = emoji,
                type = type.name,
                colorHex = color,
                sortOrder = sortOrder,
                parentId = parent?.id
            )
        )
    }

    suspend fun updateCategory(category: CategoryEntity) = categoryDao.update(category)

    suspend fun deleteCategory(category: CategoryEntity): Boolean {
        if (category.builtIn) return false
        if (categoryDao.countChildren(category.id) > 0) return false
        if (transactionDao.countForCategory(category.id) > 0) return false
        categoryDao.delete(category)
        return true
    }

    suspend fun setBudget(month: String, amountCents: Long) {
        if (amountCents <= 0) {
            budgetDao.delete(month)
        } else {
            budgetDao.upsert(BudgetEntity(month = month, amountCents = amountCents))
        }
    }

    suspend fun addRule(keyword: String, categoryId: Long): Boolean {
        return ruleDao.insert(
            AutoRuleEntity(keyword = keyword.trim(), categoryId = categoryId, builtIn = false)
        ) != -1L
    }

    /**
     * When the user corrects the category of an auto-recorded transaction, learn
     * a keyword rule so the same merchant is classified correctly next time.
     */
    suspend fun learnCategoryRule(
        previous: TransactionEntity,
        updated: TransactionEntity
    ): Boolean {
        if (previous.source == TransactionSource.MANUAL.name) return false
        if (previous.categoryId == updated.categoryId) return false
        val categoryId = updated.categoryId ?: return false
        val keyword = sequenceOf(
            updated.merchant,
            previous.merchant,
            updated.note,
            previous.note
        )
            .filterNotNull()
            .map { it.trim() }
            .firstOrNull { it.length in 2..30 }
            ?: return false

        val existing = ruleDao.findByKeyword(keyword)
        if (existing != null) {
            if (existing.categoryId == categoryId) return false
            ruleDao.update(existing.copy(categoryId = categoryId))
        } else {
            ruleDao.insert(
                AutoRuleEntity(keyword = keyword, categoryId = categoryId, builtIn = false)
            )
        }
        return true
    }

    suspend fun deleteRule(rule: AutoRuleEntity) = ruleDao.delete(rule)

    /**
     * Records a payment detected from a notification or accessibility screen.
     * Returns null when the event is a duplicate or cannot be stored.
     */
    suspend fun recordAuto(
        pkg: String,
        parsed: ParsedPayment,
        source: TransactionSource
    ): AutoRecordResult? {
        val now = System.currentTimeMillis()
        val occurredAt = parsed.occurredAtMillis?.takeIf { it > 0L } ?: now
        if (transactionDao.findDuplicateAuto(
                amountCents = parsed.amountCents,
                createdFrom = now - RECENT_DEDUP_WINDOW_MS,
                createdTo = now + 5_000L,
                occurredFrom = occurredAt - OCCURRED_DEDUP_WINDOW_MS,
                occurredTo = occurredAt + OCCURRED_DEDUP_WINDOW_MS
            ) != null
        ) {
            return null
        }

        var accounts = accountDao.getAll()
        if (accounts.isEmpty()) {
            val id = accountDao.insert(
                AccountEntity(name = "现金", type = AccountType.CASH.name)
            )
            accounts = accountDao.getAll().ifEmpty {
                listOf(AccountEntity(id = id, name = "现金", type = AccountType.CASH.name))
            }
        }
        val preferredAccountType = when (pkg) {
            com.qinghe.ledger.auto.PaymentParser.WECHAT_PACKAGE -> AccountType.WECHAT
            com.qinghe.ledger.auto.PaymentParser.ALIPAY_PACKAGE -> AccountType.ALIPAY
            else -> AccountType.CASH
        }
        val account = accounts.firstOrNull { it.type == preferredAccountType.name }
            ?: accounts.first()

        val type = if (parsed.type == PaymentType.INCOME) {
            TransactionType.INCOME
        } else {
            TransactionType.EXPENSE
        }
        val categories = categoryDao.getAll()
        val categoryById = categories.associateBy { it.id }
        val typeCategories = categories.filter { it.type == type.name }
        val topLevel = typeCategories.filter { it.parentId == null }
        val haystack = "${parsed.merchant.orEmpty()} ${parsed.rawText}"

        fun resolveSubcategory(base: CategoryEntity): CategoryEntity {
            if (base.parentId != null) return base
            val children = typeCategories.filter { it.parentId == base.id }
            if (children.isEmpty()) return base
            val byHint = children.firstOrNull { child ->
                subcategoryHints[child.name]?.any { hint -> haystack.contains(hint) } == true
            }
            val byName = children.firstOrNull { haystack.contains(it.name) }
            return byHint
                ?: byName
                ?: children.firstOrNull { it.name == "其他" }
                ?: base
        }

        val matchedCategory = ruleDao.getAll()
            .asSequence()
            .mapNotNull { rule ->
                val base = categoryById[rule.categoryId] ?: return@mapNotNull null
                if (base.type != type.name) return@mapNotNull null
                if (!haystack.contains(rule.keyword)) return@mapNotNull null
                resolveSubcategory(base)
            }
            .firstOrNull()

        val category = matchedCategory
            ?: topLevel.firstOrNull { it.name == "其他" }
            ?: topLevel.firstOrNull()

        val categoryPath = category?.let { item ->
            val parent = item.parentId?.let { categoryById[it] }
            if (parent != null) "${parent.name}-${item.name}" else item.name
        } ?: "未分类"

        val note = parsed.merchant?.takeIf { it.isNotBlank() }.orEmpty()

        val id = transactionDao.insert(
            TransactionEntity(
                type = type.name,
                amountCents = parsed.amountCents,
                categoryId = category?.id,
                accountId = account.id,
                note = note,
                merchant = parsed.merchant,
                occurredAt = occurredAt,
                createdAt = now,
                source = source.name,
                sourcePackage = pkg
            )
        )

        return AutoRecordResult(
            transactionId = id,
            amountCents = parsed.amountCents,
            type = parsed.type,
            categoryName = categoryPath,
            accountName = account.name,
            occurredAt = occurredAt,
            note = note
        )
    }

    private fun defaultAccounts(): List<AccountEntity> = listOf(
        AccountEntity(name = "现金", type = AccountType.CASH.name),
        AccountEntity(name = "微信", type = AccountType.WECHAT.name),
        AccountEntity(name = "支付宝", type = AccountType.ALIPAY.name),
        AccountEntity(name = "银行卡", type = AccountType.BANK.name)
    )

    private data class CategorySeed(
        val name: String,
        val emoji: String,
        val type: TransactionType,
        val colorHex: String,
        val parent: String? = null
    )

    private fun defaultCategorySeeds(): List<CategorySeed> = listOf(
        // 支出大类
        CategorySeed("餐饮", "🍜", TransactionType.EXPENSE, "#E07A5F"),
        CategorySeed("交通", "🚌", TransactionType.EXPENSE, "#4A7BD0"),
        CategorySeed("购物", "🛍️", TransactionType.EXPENSE, "#B07CC6"),
        CategorySeed("居住", "🏠", TransactionType.EXPENSE, "#81B29A"),
        CategorySeed("娱乐", "🎮", TransactionType.EXPENSE, "#D9A441"),
        CategorySeed("医疗", "💊", TransactionType.EXPENSE, "#D05C6B"),
        CategorySeed("学习", "📚", TransactionType.EXPENSE, "#4D908E"),
        CategorySeed("人情", "🎁", TransactionType.EXPENSE, "#E08B7E"),
        CategorySeed("通讯", "📱", TransactionType.EXPENSE, "#577590"),
        CategorySeed("其他", "📦", TransactionType.EXPENSE, "#8D99AE"),
        // 收入大类
        CategorySeed("工资", "💰", TransactionType.INCOME, "#2A9D8F"),
        CategorySeed("奖金", "🏅", TransactionType.INCOME, "#D9A441"),
        CategorySeed("理财", "📈", TransactionType.INCOME, "#457B9D"),
        CategorySeed("兼职", "💼", TransactionType.INCOME, "#E08B4F"),
        CategorySeed("红包", "🧧", TransactionType.INCOME, "#D05C6B"),
        CategorySeed("其他", "💵", TransactionType.INCOME, "#8D99AE"),
        // 支出小类
        CategorySeed("早餐", "🥐", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("午餐", "🍱", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("晚餐", "🍲", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("外卖", "🥡", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("咖啡", "☕", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("零食", "🍪", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("一卡通", "🎫", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("其他", "🍴", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("公交地铁", "🚇", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("打车", "🚕", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("电动车", "🛵", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("加油", "⛽", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("停车", "🅿️", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("火车高铁", "🚄", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("其他", "🧭", TransactionType.EXPENSE, "#4A7BD0", "交通"),
        CategorySeed("日用", "🧴", TransactionType.EXPENSE, "#B07CC6", "购物"),
        CategorySeed("服饰", "👕", TransactionType.EXPENSE, "#B07CC6", "购物"),
        CategorySeed("数码", "💻", TransactionType.EXPENSE, "#B07CC6", "购物"),
        CategorySeed("美妆", "💄", TransactionType.EXPENSE, "#B07CC6", "购物"),
        CategorySeed("其他", "🛒", TransactionType.EXPENSE, "#B07CC6", "购物"),
        CategorySeed("房租", "🏠", TransactionType.EXPENSE, "#81B29A", "居住"),
        CategorySeed("水电", "💡", TransactionType.EXPENSE, "#81B29A", "居住"),
        CategorySeed("物业", "🏢", TransactionType.EXPENSE, "#81B29A", "居住"),
        CategorySeed("家居", "🛋️", TransactionType.EXPENSE, "#81B29A", "居住"),
        CategorySeed("其他", "🔑", TransactionType.EXPENSE, "#81B29A", "居住"),
        CategorySeed("电影", "🎬", TransactionType.EXPENSE, "#D9A441", "娱乐"),
        CategorySeed("游戏", "🎮", TransactionType.EXPENSE, "#D9A441", "娱乐"),
        CategorySeed("旅行", "✈️", TransactionType.EXPENSE, "#D9A441", "娱乐"),
        CategorySeed("运动", "🏃", TransactionType.EXPENSE, "#D9A441", "娱乐"),
        CategorySeed("其他", "🎉", TransactionType.EXPENSE, "#D9A441", "娱乐"),
        CategorySeed("药品", "💊", TransactionType.EXPENSE, "#D05C6B", "医疗"),
        CategorySeed("门诊", "🏥", TransactionType.EXPENSE, "#D05C6B", "医疗"),
        CategorySeed("体检", "🩺", TransactionType.EXPENSE, "#D05C6B", "医疗"),
        CategorySeed("其他", "🧾", TransactionType.EXPENSE, "#D05C6B", "医疗"),
        CategorySeed("书籍", "📚", TransactionType.EXPENSE, "#4D908E", "学习"),
        CategorySeed("课程", "🎓", TransactionType.EXPENSE, "#4D908E", "学习"),
        CategorySeed("云服务", "☁️", TransactionType.EXPENSE, "#4D908E", "学习"),
        CategorySeed("文具", "✏️", TransactionType.EXPENSE, "#4D908E", "学习"),
        CategorySeed("其他", "📖", TransactionType.EXPENSE, "#4D908E", "学习"),
        CategorySeed("红包", "🧧", TransactionType.EXPENSE, "#E08B7E", "人情"),
        CategorySeed("礼物", "🎁", TransactionType.EXPENSE, "#E08B7E", "人情"),
        CategorySeed("请客", "🍽️", TransactionType.EXPENSE, "#E08B7E", "人情"),
        CategorySeed("亲属卡", "💳", TransactionType.EXPENSE, "#E08B7E", "人情"),
        CategorySeed("其他", "🤝", TransactionType.EXPENSE, "#E08B7E", "人情"),
        CategorySeed("话费", "📱", TransactionType.EXPENSE, "#577590", "通讯"),
        CategorySeed("流量", "📶", TransactionType.EXPENSE, "#577590", "通讯"),
        CategorySeed("宽带", "🌐", TransactionType.EXPENSE, "#577590", "通讯"),
        CategorySeed("其他", "📞", TransactionType.EXPENSE, "#577590", "通讯"),
        // 收入小类
        CategorySeed("利息", "🏦", TransactionType.INCOME, "#457B9D", "理财"),
        CategorySeed("投资收益", "📈", TransactionType.INCOME, "#457B9D", "理财"),
        CategorySeed("其他", "💹", TransactionType.INCOME, "#457B9D", "理财")
    )

    private fun defaultRules(): List<Triple<String, String, TransactionType>> = listOf(
        Triple("美团", "餐饮", TransactionType.EXPENSE),
        Triple("饿了么", "餐饮", TransactionType.EXPENSE),
        Triple("肯德基", "餐饮", TransactionType.EXPENSE),
        Triple("麦当劳", "餐饮", TransactionType.EXPENSE),
        Triple("星巴克", "餐饮", TransactionType.EXPENSE),
        Triple("瑞幸", "餐饮", TransactionType.EXPENSE),
        Triple("咖啡", "餐饮", TransactionType.EXPENSE),
        Triple("奶茶", "餐饮", TransactionType.EXPENSE),
        Triple("蜜雪冰城", "餐饮", TransactionType.EXPENSE),
        Triple("餐厅", "餐饮", TransactionType.EXPENSE),
        Triple("外卖", "餐饮", TransactionType.EXPENSE),
        Triple("滴滴", "交通", TransactionType.EXPENSE),
        Triple("高德打车", "交通", TransactionType.EXPENSE),
        Triple("出租车", "交通", TransactionType.EXPENSE),
        Triple("地铁", "交通", TransactionType.EXPENSE),
        Triple("公交", "交通", TransactionType.EXPENSE),
        Triple("高铁", "交通", TransactionType.EXPENSE),
        Triple("火车", "交通", TransactionType.EXPENSE),
        Triple("加油", "交通", TransactionType.EXPENSE),
        Triple("停车", "交通", TransactionType.EXPENSE),
        Triple("充电桩", "交通", TransactionType.EXPENSE),
        Triple("淘宝", "购物", TransactionType.EXPENSE),
        Triple("天猫", "购物", TransactionType.EXPENSE),
        Triple("京东", "购物", TransactionType.EXPENSE),
        Triple("拼多多", "购物", TransactionType.EXPENSE),
        Triple("超市", "购物", TransactionType.EXPENSE),
        Triple("便利店", "购物", TransactionType.EXPENSE),
        Triple("盒马", "购物", TransactionType.EXPENSE),
        Triple("山姆", "购物", TransactionType.EXPENSE),
        Triple("房租", "居住", TransactionType.EXPENSE),
        Triple("物业", "居住", TransactionType.EXPENSE),
        Triple("水费", "居住", TransactionType.EXPENSE),
        Triple("电费", "居住", TransactionType.EXPENSE),
        Triple("燃气", "居住", TransactionType.EXPENSE),
        Triple("电影", "娱乐", TransactionType.EXPENSE),
        Triple("游戏", "娱乐", TransactionType.EXPENSE),
        Triple("演出", "娱乐", TransactionType.EXPENSE),
        Triple("KTV", "娱乐", TransactionType.EXPENSE),
        Triple("爱奇艺", "娱乐", TransactionType.EXPENSE),
        Triple("腾讯视频", "娱乐", TransactionType.EXPENSE),
        Triple("医院", "医疗", TransactionType.EXPENSE),
        Triple("药房", "医疗", TransactionType.EXPENSE),
        Triple("药店", "医疗", TransactionType.EXPENSE),
        Triple("诊所", "医疗", TransactionType.EXPENSE),
        Triple("书店", "学习", TransactionType.EXPENSE),
        Triple("课程", "学习", TransactionType.EXPENSE),
        Triple("学费", "学习", TransactionType.EXPENSE),
        Triple("培训", "学习", TransactionType.EXPENSE),
        Triple("话费", "通讯", TransactionType.EXPENSE),
        Triple("流量", "通讯", TransactionType.EXPENSE),
        Triple("中国移动", "通讯", TransactionType.EXPENSE),
        Triple("中国联通", "通讯", TransactionType.EXPENSE),
        Triple("中国电信", "通讯", TransactionType.EXPENSE),
        Triple("工资", "工资", TransactionType.INCOME),
        Triple("奖金", "奖金", TransactionType.INCOME),
        Triple("红包", "红包", TransactionType.INCOME),
        Triple("利息", "理财", TransactionType.INCOME),
        Triple("理财", "理财", TransactionType.INCOME),
        Triple("收益", "理财", TransactionType.INCOME)
    )

    companion object {
        private const val RECENT_DEDUP_WINDOW_MS = 90_000L
        private const val OCCURRED_DEDUP_WINDOW_MS = 120_000L
        private val categoryPalette = listOf(
            "#E07A5F", "#4A7BD0", "#B07CC6", "#81B29A", "#D9A441",
            "#D05C6B", "#4D908E", "#577590", "#8D99AE"
        )
        private val subcategoryHints = mapOf(
            "早餐" to listOf("早餐", "包子", "豆浆"),
            "午餐" to listOf("午餐", "午饭"),
            "晚餐" to listOf("晚餐", "晚饭"),
            "外卖" to listOf("外卖", "美团", "饿了么", "肯德基", "麦当劳"),
            "咖啡" to listOf("咖啡", "瑞幸", "星巴克", "奶茶", "蜜雪冰城"),
            "零食" to listOf("零食", "小吃"),
            "一卡通" to listOf("一卡通", "校园卡", "饭卡"),
            "公交地铁" to listOf("地铁", "公交", "轨道交通"),
            "打车" to listOf("打车", "滴滴", "出租", "高德"),
            "电动车" to listOf("电动车", "电瓶车", "小电驴"),
            "加油" to listOf("加油", "中石化", "中石油"),
            "停车" to listOf("停车"),
            "火车高铁" to listOf("火车", "高铁", "12306"),
            "日用" to listOf("超市", "便利店", "日用"),
            "服饰" to listOf("服饰", "服装", "鞋"),
            "数码" to listOf("数码", "电脑", "手机"),
            "美妆" to listOf("美妆", "化妆", "护肤"),
            "房租" to listOf("房租", "租金"),
            "水电" to listOf("水费", "电费", "燃气", "水电"),
            "物业" to listOf("物业"),
            "家居" to listOf("家居", "家具"),
            "电影" to listOf("电影", "影院"),
            "游戏" to listOf("游戏", "Steam", "王者"),
            "旅行" to listOf("旅行", "酒店", "机票"),
            "运动" to listOf("健身", "运动"),
            "药品" to listOf("药房", "药店", "药品"),
            "门诊" to listOf("医院", "门诊", "挂号"),
            "体检" to listOf("体检"),
            "书籍" to listOf("书店", "书籍", "买书"),
            "课程" to listOf("课程", "学费", "培训"),
            "云服务" to listOf("云服务", "云盘", "iCloud", "阿里云", "腾讯云", "百度网盘"),
            "文具" to listOf("文具"),
            "红包" to listOf("红包"),
            "礼物" to listOf("礼物"),
            "请客" to listOf("请客", "聚餐"),
            "亲属卡" to listOf("亲属卡"),
            "话费" to listOf("话费", "充值"),
            "流量" to listOf("流量"),
            "宽带" to listOf("宽带"),
            "利息" to listOf("利息"),
            "投资收益" to listOf("收益", "理财")
        )
    }
}
