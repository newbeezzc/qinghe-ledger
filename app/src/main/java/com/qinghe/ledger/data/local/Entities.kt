package com.qinghe.ledger.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.qinghe.ledger.data.model.AccountType
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = AccountType.CASH.name,
    val initialBalanceCents: Long = 0,
    val sortOrder: Int = 0,
    val archived: Boolean = false
)

@Entity(tableName = "categories", indices = [Index("type")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val type: String = TransactionType.EXPENSE.name,
    val colorHex: String = "#8D99AE",
    val sortOrder: Int = 0,
    val builtIn: Boolean = false,
    val parentId: Long? = null
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredAt"),
        Index("categoryId"),
        Index("accountId"),
        Index("sourcePackage")
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String = TransactionType.EXPENSE.name,
    val amountCents: Long,
    val categoryId: Long? = null,
    val accountId: Long,
    val toAccountId: Long? = null,
    val note: String = "",
    val merchant: String? = null,
    val occurredAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val source: String = TransactionSource.MANUAL.name,
    val sourcePackage: String? = null
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val month: String,
    val amountCents: Long
)

@Entity(tableName = "auto_rules", indices = [Index(value = ["keyword"], unique = true)])
data class AutoRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val categoryId: Long,
    val builtIn: Boolean = false
)
