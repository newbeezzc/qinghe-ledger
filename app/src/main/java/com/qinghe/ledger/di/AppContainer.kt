package com.qinghe.ledger.di

import android.content.Context
import com.qinghe.ledger.auto.AutoRecorder
import com.qinghe.ledger.data.LedgerRepository
import com.qinghe.ledger.data.SettingsRepository
import com.qinghe.ledger.data.local.AppDatabase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.get(appContext)
    val repository = LedgerRepository(database)
    val settings = SettingsRepository(appContext)
    val autoRecorder = AutoRecorder(appContext, repository, settings)
}
