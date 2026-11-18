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
import com.qinghe.ledger.data.local.WidgetSummary
import com.qinghe.ledger.data.model.AccountType
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.util.endMillis
import com.qinghe.ledger.util.startMillis
import com.qinghe.ledger.util.toEpochMillis
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable subcategory order: normal rows by sortOrder, then 其他 last. */
internal fun List<CategoryEntity>.sortedKeepingOtherLast(): List<CategoryEntity> =
    sortedWith(compareBy({ it.name == "其他" }, { it.sortOrder }, { it.id }))

data class AutoRecordResult(
    val transactionId: Long,
    val amountCents: Long,
    val type: PaymentType,
    val categoryName: String,
    val accountName: String,
    val occurredAt: Long,
    val note: String = ""
)

data class QuickEntryResult(
    val amountCents: Long?,
    val type: TransactionType,
    val occurredAtMillis: Long,
    val category: CategoryEntity?,
    val note: String,
    val reason: String
)

class LedgerRepository(
    private val db: AppDatabase,
    private val onTransactionsChanged: () -> Unit = {}
) {
    private val accountDao = db.accountDao()
    private val categoryDao = db.categoryDao()
    private val transactionDao = db.transactionDao()
    private val budgetDao = db.budgetDao()
    private val ruleDao = db.autoRuleDao()

    private val seedMutex = Mutex()

    private fun notifyTransactionsChanged() {
        runCatching { onTransactionsChanged() }
    }

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
        migrateRetiredMealSubcategories()
        ensureOtherLast()
    }

    /**
     * Retires 餐饮-外卖 and 餐饮-咖啡 in favour of 酒水饮料. References are moved
     * (咖啡 → 酒水饮料, 外卖 → 其他) before deletion so no transaction or learned
     * rule is left pointing at a missing category. Idempotent.
     */
    private suspend fun migrateRetiredMealSubcategories() {
        val all = categoryDao.getAll()
        val meal = all.firstOrNull {
            it.parentId == null && it.name == "餐饮" && it.type == TransactionType.EXPENSE.name
        } ?: return
        val children = all.filter { it.parentId == meal.id }
        val other = children.firstOrNull { it.name == "其他" } ?: return
        val drinks = children.firstOrNull { it.name == "酒水饮料" } ?: return
        children
            .filter { it.builtIn && (it.name == "外卖" || it.name == "咖啡") }
            .forEach { retired ->
                val target = if (retired.name == "咖啡") drinks else other
                transactionDao.reassignCategory(retired.id, target.id)
                ruleDao.reassignCategory(retired.id, target.id)
                categoryDao.delete(retired)
            }
    }

    /** Keeps 其他 at the end of every subcategory group after additions/deletions. */
    private suspend fun ensureOtherLast() {
        categoryDao.getAll()
            .filter { it.parentId != null }
            .groupBy { it.parentId }
            .forEach { (_, children) ->
                children.sortedKeepingOtherLast().forEachIndexed { index, child ->
                    if (child.sortOrder != index) {
                        categoryDao.update(child.copy(sortOrder = index))
                    }
                }
            }
    }

    suspend fun addTransaction(transaction: TransactionEntity): Long {
        val id = transactionDao.insert(transaction)
        notifyTransactionsChanged()
        return id
    }

    suspend fun updateTransaction(transaction: TransactionEntity) {
        transactionDao.update(transaction)
        notifyTransactionsChanged()
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        transactionDao.delete(transaction)
        notifyTransactionsChanged()
    }

    /** Deletes an auto-recorded entry, e.g. when the user taps 取消记账 on the overlay. */
    suspend fun deleteAutoTransaction(id: Long): Boolean {
        val transaction = transactionDao.findById(id) ?: return false
        if (transaction.source == TransactionSource.MANUAL.name) return false
        transactionDao.delete(transaction)
        notifyTransactionsChanged()
        return true
    }

    suspend fun findTransaction(id: Long): TransactionEntity? =
        transactionDao.findById(id)

    /** Today's expense plus this month's expense/income for the home-screen widget. */
    suspend fun widgetSummary(nowMillis: Long = System.currentTimeMillis()): WidgetSummary {
        val today = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val month = YearMonth.from(today)
        return transactionDao.widgetSummary(
            todayStart = today.toEpochMillis(),
            todayEnd = today.plusDays(1).toEpochMillis(),
            monthStart = month.startMillis(),
            monthEnd = month.endMillis()
        )
    }

    /** Parses the widget's free-text input and resolves its category. */
    suspend fun parseQuickEntry(
        text: String,
        typeOverride: TransactionType? = null
    ): QuickEntryResult {
        val parsed = QuickTextParser.parse(text)
        val type = typeOverride ?: when (parsed.type) {
            PaymentType.INCOME -> TransactionType.INCOME
            PaymentType.EXPENSE -> TransactionType.EXPENSE
        }
        if (text.isBlank() || parsed.amountCents == null) {
            return QuickEntryResult(
                amountCents = null,
                type = type,
                occurredAtMillis = parsed.occurredAtMillis,
                category = null,
                note = parsed.note,
                reason = parsed.reason
            )
        }
        val categories = categoryDao.getAll()
        val category = AutoCategoryClassifier.classify(
            type = type,
            merchant = null,
            rawText = text,
            occurredAtMillis = parsed.occurredAtMillis,
            categories = categories,
            rules = ruleDao.getAll()
        )?.category
        return QuickEntryResult(
            amountCents = parsed.amountCents,
            type = type,
            occurredAtMillis = parsed.occurredAtMillis,
            category = category,
            note = parsed.note,
            reason = parsed.reason
        )
    }

    /**
     * The widget entry always records to cash. If the user removed the cash
     * account, fall back to the first account and only create one when the
     * ledger has no accounts at all.
     */
    suspend fun ensureCashAccount(): AccountEntity = seedMutex.withLock {
        val accounts = accountDao.getAll()
        accounts.firstOrNull { it.type == AccountType.CASH.name }?.let { return@withLock it }
        accounts.firstOrNull()?.let { return@withLock it }
        val id = accountDao.insert(
            AccountEntity(name = "现金", type = AccountType.CASH.name)
        )
        AccountEntity(id = id, name = "现金", type = AccountType.CASH.name)
    }

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
        val id = categoryDao.insert(
            CategoryEntity(
                name = name.trim(),
                emoji = emoji,
                type = type.name,
                colorHex = color,
                sortOrder = sortOrder,
                parentId = parent?.id
            )
        )
        ensureOtherLast()
        return id
    }

    suspend fun updateCategory(category: CategoryEntity) {
        categoryDao.update(category)
        ensureOtherLast()
    }

    suspend fun deleteCategory(category: CategoryEntity): Boolean {
        if (category.builtIn) return false
        if (categoryDao.countChildren(category.id) > 0) return false
        if (transactionDao.countForCategory(category.id) > 0) return false
        categoryDao.delete(category)
        ensureOtherLast()
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
     * merchant → category rules so the same store is classified correctly next
     * time. Both the raw merchant name and its normalized key (branch / company
     * suffix removed) are stored, so "瑞幸咖啡(万达店)" and "瑞幸咖啡" match each
     * other. Platform-only names (美团/淘宝/…) are never learned.
     */
    suspend fun learnCategoryRule(
        previous: TransactionEntity,
        updated: TransactionEntity
    ): Boolean {
        if (previous.source == TransactionSource.MANUAL.name) return false
        if (previous.categoryId == updated.categoryId) return false
        val categoryId = updated.categoryId ?: return false

        val merchants = sequenceOf(updated.merchant, previous.merchant)
            .filterNotNull()
            .map { it.trim() }
            .filter { AutoCategoryClassifier.isLearnableMerchant(it) }
            .toList()
        val notes = sequenceOf(updated.note, previous.note)
            .filterNotNull()
            .map { it.trim() }
            .filter { it.length in 2..12 }
            .filter { AutoCategoryClassifier.isLearnableMerchant(it) }
            .toList()

        val keywords = buildList {
            addAll(merchants.take(1))
            merchants.asSequence()
                .map { AutoCategoryClassifier.merchantKey(it) }
                .filter { it.length in 3..30 && AutoCategoryClassifier.isLearnableMerchant(it) }
                .take(1)
                .forEach { add(it) }
            if (isEmpty()) addAll(notes.take(1))
        }.distinct()

        if (keywords.isEmpty()) return false

        var learned = false
        keywords.forEach { keyword ->
            val existing = ruleDao.findByKeyword(keyword)
            if (existing != null) {
                if (existing.categoryId != categoryId) {
                    // A user correction turns a seeded rule into a user rule.
                    ruleDao.update(existing.copy(categoryId = categoryId, builtIn = false))
                    learned = true
                }
            } else {
                ruleDao.insert(
                    AutoRuleEntity(keyword = keyword, categoryId = categoryId, builtIn = false)
                )
                learned = true
            }
        }
        return learned
    }

    suspend fun deleteRule(rule: AutoRuleEntity) = ruleDao.delete(rule)

    /**
     * Records a payment detected from a notification or accessibility screen.
     * Returns null when the event is a duplicate or cannot be stored.
     *
     * Duplicate means the same amount was auto-recorded moments ago (the
     * notification and screen paths both firing), or any existing record —
     * including a manual one — has the same amount at the same payment time.
     * Old bill detail pages are still recognised: they insert only when that
     * payment is not already in the ledger.
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
            in com.qinghe.ledger.auto.PaymentParser.douyinPackages -> AccountType.DOUYIN
            in com.qinghe.ledger.auto.PaymentParser.meituanPackages -> AccountType.MEITUAN
            else -> AccountType.CASH
        }
        var account = accounts.firstOrNull { it.type == preferredAccountType.name }
        if (account == null &&
            preferredAccountType in listOf(AccountType.DOUYIN, AccountType.MEITUAN)
        ) {
            // First purchase in a new source app: create a dedicated wallet
            // account so the record does not silently fall into cash.
            val id = accountDao.insert(
                AccountEntity(
                    name = preferredAccountType.label,
                    type = preferredAccountType.name,
                    sortOrder = accounts.size
                )
            )
            account = AccountEntity(
                id = id,
                name = preferredAccountType.label,
                type = preferredAccountType.name
            )
        }
        account = account ?: accounts.first()

        val type = if (parsed.type == PaymentType.INCOME) {
            TransactionType.INCOME
        } else {
            TransactionType.EXPENSE
        }
        val categories = categoryDao.getAll()
        val categoryById = categories.associateBy { it.id }
        val classifierMatch = AutoCategoryClassifier.classify(
            type = type,
            merchant = parsed.merchant,
            rawText = parsed.rawText,
            occurredAtMillis = occurredAt,
            categories = categories,
            rules = ruleDao.getAll()
        )
        var category = classifierMatch?.category
        // Merchant → category memory: if the classifier can only say "其他",
        // reuse the category the user picked for this merchant before.
        val merchantKey = parsed.merchant?.trim().orEmpty()
        if ((category == null || category.name == "其他") &&
            merchantKey.length >= 2 &&
            AutoCategoryClassifier.isLearnableMerchant(merchantKey)
        ) {
            category = transactionDao.findRecentCategoryIdsForKey(merchantKey)
                .mapNotNull { categoryById[it] }
                .firstOrNull { it.type == type.name && it.name != "其他" }
                ?: category
        }

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
        notifyTransactionsChanged()

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
        AccountEntity(name = "抖音", type = AccountType.DOUYIN.name),
        AccountEntity(name = "美团", type = AccountType.MEITUAN.name),
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
        CategorySeed("零食", "🍪", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("一卡通", "🎫", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
        CategorySeed("酒水饮料", "🍹", TransactionType.EXPENSE, "#E07A5F", "餐饮"),
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
    }
}
