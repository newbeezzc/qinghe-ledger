package com.qinghe.ledger.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class AccountDelta(
    val accountId: Long,
    val delta: Long
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY sortOrder, id")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query(
        """
        SELECT accountId AS accountId,
               CASE WHEN type = 'INCOME' THEN amountCents ELSE -amountCents END AS delta
        FROM transactions
        WHERE type IN ('EXPENSE', 'INCOME')
        UNION ALL
        SELECT accountId AS accountId, -amountCents AS delta
        FROM transactions
        WHERE type = 'TRANSFER'
        UNION ALL
        SELECT toAccountId AS accountId, amountCents AS delta
        FROM transactions
        WHERE type = 'TRANSFER' AND toAccountId IS NOT NULL
        """
    )
    fun observeDeltas(): Flow<List<AccountDelta>>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY type, sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type")
    suspend fun countByType(type: String): Int

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY sortOrder, id")
    suspend fun childrenOf(parentId: Long): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories WHERE parentId = :parentId")
    suspend fun countChildren(parentId: Long): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)
}

data class CategoryTotal(
    val categoryId: Long?,
    val total: Long
)

@Dao
interface TransactionDao {
    @Query(
        """
        SELECT * FROM transactions
        WHERE occurredAt >= :start AND occurredAt < :end
        ORDER BY occurredAt DESC, id DESC
        """
    )
    fun observeBetween(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun findById(id: Long): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE source != 'MANUAL'
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    fun observeLatestAuto(): Flow<TransactionEntity?>

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId OR toAccountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query(
        """
        SELECT * FROM transactions
        WHERE source != 'MANUAL'
          AND amountCents = :amountCents
          AND (
            createdAt BETWEEN :createdFrom AND :createdTo
            OR occurredAt BETWEEN :occurredFrom AND :occurredTo
          )
        LIMIT 1
        """
    )
    suspend fun findDuplicateAuto(
        amountCents: Long,
        createdFrom: Long,
        createdTo: Long,
        occurredFrom: Long,
        occurredTo: Long
    ): TransactionEntity?
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE month = :month")
    fun observe(month: String): Flow<BudgetEntity?>

    @Query("SELECT * FROM budgets WHERE month = :month")
    suspend fun get(month: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE month = :month")
    suspend fun delete(month: String)
}

@Dao
interface AutoRuleDao {
    @Query("SELECT * FROM auto_rules ORDER BY length(keyword) DESC, id")
    fun observeAll(): Flow<List<AutoRuleEntity>>

    @Query("SELECT * FROM auto_rules ORDER BY length(keyword) DESC, id")
    suspend fun getAll(): List<AutoRuleEntity>

    @Query("SELECT COUNT(*) FROM auto_rules")
    suspend fun count(): Int

    @Query("SELECT * FROM auto_rules WHERE keyword = :keyword LIMIT 1")
    suspend fun findByKeyword(keyword: String): AutoRuleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rule: AutoRuleEntity): Long

    @Update
    suspend fun update(rule: AutoRuleEntity)

    @Delete
    suspend fun delete(rule: AutoRuleEntity)
}
