package com.qinghe.ledger.auto

import com.qinghe.ledger.data.AutoRecordResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Emits successfully auto-recorded entries so a visible app can show a snackbar.
 * Process-local; system notifications cover the background case.
 */
object AutoRecordEvents {
    private val _events = MutableSharedFlow<AutoRecordResult>(extraBufferCapacity = 8)
    val events: SharedFlow<AutoRecordResult> = _events.asSharedFlow()

    fun notify(result: AutoRecordResult) {
        _events.tryEmit(result)
    }
}
