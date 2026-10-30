package com.qinghe.ledger.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.ui.screens.AddTransactionScreen
import com.qinghe.ledger.ui.theme.QingheTheme
import com.qinghe.ledger.util.AppVisibility
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Opens the normal bookkeeping screen directly on top of WeChat/Alipay after an
 * automatic record. The same Compose screen used by MainActivity is reused, so
 * amount keypad, two-level categories, accounts and notes behave identically.
 */
class QuickEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)
        if (transactionId <= 0L) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            QingheTheme {
                QuickEditHost(
                    transactionId = transactionId,
                    onFinish = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.isForeground = true
    }

    override fun onPause() {
        AppVisibility.isForeground = false
        super.onPause()
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "quick_edit_transaction_id"

        fun start(context: Context, transactionId: Long): Boolean {
            val intent = Intent(context, QuickEditActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
            }
            return runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}

@Composable
private fun QuickEditHost(
    transactionId: Long,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as QingheApp).container.repository
    }
    val scope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }
    var balances by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }

    LaunchedEffect(transactionId) {
        val loaded = repository.findTransaction(transactionId)
        if (loaded == null) {
            Toast.makeText(context, "这笔账目已不存在", Toast.LENGTH_SHORT).show()
            onFinish()
            return@LaunchedEffect
        }
        categories = repository.categories.first()
        accounts = repository.accounts.first()
        val deltas = repository.accountDeltas.first()
        balances = accounts.associate { account ->
            account.id to (
                account.initialBalanceCents +
                    deltas.filter { it.accountId == account.id }.sumOf { it.delta }
                )
        }
        transaction = loaded
    }

    val current = transaction
    if (current == null) {
        LoadingScreen()
        return
    }

    AddTransactionScreen(
        existing = current,
        categories = categories,
        accounts = accounts,
        accountBalances = balances,
        defaultAccountId = current.accountId,
        autoConfirm = true,
        onDismiss = onFinish,
        onSave = { updated ->
            scope.launch {
                runCatching {
                    repository.updateTransaction(updated)
                    repository.learnCategoryRule(current, updated)
                }.onSuccess {
                    Toast.makeText(context, "已记录", Toast.LENGTH_SHORT).show()
                    onFinish()
                }.onFailure {
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDelete = { target ->
            scope.launch {
                val deleted = repository.deleteAutoTransaction(target.id)
                Toast.makeText(
                    context,
                    if (deleted) "已取消记录" else "取消失败，请重试",
                    Toast.LENGTH_SHORT
                ).show()
                if (deleted) onFinish()
            }
        },
        onValidationError = { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    )
}

@Composable
private fun LoadingScreen() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "正在加载记录…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
