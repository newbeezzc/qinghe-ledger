package com.qinghe.ledger.auto

import android.content.Context
import com.qinghe.ledger.data.AutoRecordResult
import com.qinghe.ledger.data.LedgerRepository
import com.qinghe.ledger.data.SettingsRepository
import com.qinghe.ledger.data.model.TransactionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutoRecorder(
    private val context: Context,
    private val repository: LedgerRepository,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Notification and accessibility events can arrive at almost the same time.
    // Serialize "check duplicate + insert" so a payment is only recorded once.
    private val recordMutex = Mutex()

    fun handle(pkg: String, parsed: ParsedPayment, source: TransactionSource) {
        if (source == TransactionSource.MANUAL) return
        scope.launch {
            recordMutex.withLock {
                val current = settings.settings.first()
                val enabled = when (source) {
                    TransactionSource.NOTIFICATION -> current.notificationAutoEnabled
                    TransactionSource.ACCESSIBILITY -> current.accessibilityAutoEnabled
                    TransactionSource.MANUAL -> false
                }
                if (!enabled) return@withLock

                val result: AutoRecordResult =
                    repository.recordAuto(pkg, parsed, source) ?: return@withLock
                if (current.showAutoRecordNotification) {
                    val overlayShown = AutoRecordOverlay.show(context, result)
                    if (!overlayShown) {
                        AutoRecordEvents.notify(result)
                        AutoRecordNotifier.notify(context, result, source)
                    }
                }
            }
        }
    }
}
