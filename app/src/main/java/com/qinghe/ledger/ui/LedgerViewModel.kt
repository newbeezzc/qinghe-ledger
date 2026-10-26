package com.qinghe.ledger.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.AutoSettings
import com.qinghe.ledger.data.LedgerRepository
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.AutoRuleEntity
import com.qinghe.ledger.data.local.BudgetEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.AccountType
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.util.monthKey
import com.qinghe.ledger.util.startMillis
import com.qinghe.ledger.util.endMillis
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MonthSummary(
    val expenseCents: Long = 0,
    val incomeCents: Long = 0,
    val balanceCents: Long = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: LedgerRepository =
        (application as QingheApp).container.repository
    private val settingsRepository =
        (application as QingheApp).container.settings

    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: MutableSharedFlow<String> = _messages

    val transactions: StateFlow<List<TransactionEntity>> = _selectedMonth
        .flatMapLatest { month ->
            repository.transactionsBetween(month.startMillis(), month.endMillis())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summary: StateFlow<MonthSummary> = transactions
        .map { list ->
            var expense = 0L
            var income = 0L
            list.forEach { transaction ->
                when (TransactionType.from(transaction.type)) {
                    TransactionType.EXPENSE -> expense += transaction.amountCents
                    TransactionType.INCOME -> income += transaction.amountCents
                    TransactionType.TRANSFER -> Unit
                }
            }
            MonthSummary(
                expenseCents = expense,
                incomeCents = income,
                balanceCents = income - expense
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonthSummary())

    val accounts: StateFlow<List<AccountEntity>> = repository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<AutoRuleEntity>> = repository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val latestAutoTransaction: StateFlow<TransactionEntity?> =
        repository.latestAutoTransaction
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val budget: StateFlow<BudgetEntity?> = _selectedMonth
        .flatMapLatest { repository.observeBudget(it.monthKey()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accountBalances: StateFlow<Map<Long, Long>> =
        combine(accounts, repository.accountDeltas) { accountList, deltas ->
            val deltaById = deltas.groupBy { it.accountId }
                .mapValues { (_, rows) -> rows.sumOf { it.delta } }
            accountList.associate { account ->
                account.id to (account.initialBalanceCents + (deltaById[account.id] ?: 0L))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val settings: StateFlow<AutoSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoSettings())

    fun previousMonth() = _selectedMonth.update { it.minusMonths(1) }

    fun nextMonth() = _selectedMonth.update { it.plusMonths(1) }

    fun goToCurrentMonth() = _selectedMonth.update { YearMonth.now() }

    fun saveTransaction(
        transaction: TransactionEntity,
        previous: TransactionEntity?
    ) {
        viewModelScope.launch {
            if (previous == null) {
                repository.addTransaction(transaction)
                _messages.tryEmit("已记一笔")
            } else {
                repository.updateTransaction(transaction)
                val learned = repository.learnCategoryRule(previous, transaction)
                _messages.tryEmit(
                    if (learned) "已保存修改，并记住该商户分类" else "已保存修改"
                )
            }
        }
    }

    suspend fun findTransaction(id: Long): TransactionEntity? =
        repository.findTransaction(id)

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            _messages.tryEmit("已删除")
        }
    }

    fun addAccount(name: String, type: AccountType, initialBalanceCents: Long) {
        viewModelScope.launch {
            repository.addAccount(name, type, initialBalanceCents)
            _messages.tryEmit("账户已添加")
        }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch {
            repository.updateAccount(account)
            _messages.tryEmit("账户已保存")
        }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch {
            val deleted = repository.deleteAccount(account)
            _messages.tryEmit(if (deleted) "账户已删除" else "该账户下已有账目，无法删除")
        }
    }

    fun addCategory(
        name: String,
        emoji: String,
        type: TransactionType,
        parentId: Long? = null
    ) {
        viewModelScope.launch {
            repository.addCategory(name, emoji, type, parentId)
            _messages.tryEmit("分类已添加")
        }
    }

    fun updateCategory(category: CategoryEntity) {
        viewModelScope.launch {
            repository.updateCategory(category)
            _messages.tryEmit("分类已保存")
        }
    }

    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            val deleted = repository.deleteCategory(category)
            _messages.tryEmit(
                when {
                    deleted -> "分类已删除"
                    category.builtIn -> "内置分类不能删除"
                    else -> "该分类含小类或已有账目，无法删除"
                }
            )
        }
    }

    fun setBudget(month: YearMonth, amountCents: Long) {
        viewModelScope.launch {
            repository.setBudget(month.monthKey(), amountCents)
            _messages.tryEmit(if (amountCents > 0) "预算已保存" else "预算已清除")
        }
    }

    fun addRule(keyword: String, categoryId: Long) {
        viewModelScope.launch {
            val added = repository.addRule(keyword, categoryId)
            _messages.tryEmit(if (added) "规则已添加" else "规则已存在")
        }
    }

    fun deleteRule(rule: AutoRuleEntity) {
        viewModelScope.launch {
            repository.deleteRule(rule)
            _messages.tryEmit("规则已删除")
        }
    }

    fun setNotificationAutoEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationAutoEnabled(enabled) }
    }

    fun setAccessibilityAutoEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAccessibilityAutoEnabled(enabled) }
    }

    fun setShowAutoNotification(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setShowAutoRecordNotification(enabled) }
    }

    fun setGithubRepo(repo: String) {
        viewModelScope.launch { settingsRepository.setGithubRepo(repo) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoCheckUpdates(enabled) }
    }

    fun markUpdateChecked() {
        viewModelScope.launch {
            settingsRepository.setLastUpdateCheck(System.currentTimeMillis())
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
                        return LedgerViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
                }
            }
    }
}
