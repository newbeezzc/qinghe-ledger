package com.qinghe.ledger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qinghe.ledger.ui.LedgerRoot
import com.qinghe.ledger.ui.theme.QingheTheme
import com.qinghe.ledger.util.AppVisibility
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    val editTransactionId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        editTransactionId.value = readEditTransactionId(intent)
        setContent {
            QingheTheme {
                LedgerRoot(editTransactionId = editTransactionId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        editTransactionId.value = readEditTransactionId(intent)
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.isForeground = true
    }

    override fun onPause() {
        AppVisibility.isForeground = false
        super.onPause()
    }

    private fun readEditTransactionId(intent: Intent?): Long? =
        intent?.getLongExtra(EXTRA_EDIT_TRANSACTION_ID, 0L)?.takeIf { it > 0L }

    companion object {
        const val EXTRA_EDIT_TRANSACTION_ID = "edit_transaction_id"
    }
}
