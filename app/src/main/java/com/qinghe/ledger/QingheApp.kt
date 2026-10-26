package com.qinghe.ledger

import android.app.Application
import com.qinghe.ledger.auto.AutoRecordNotifier
import com.qinghe.ledger.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QingheApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AutoRecordNotifier.createChannel(this)
        appScope.launch {
            container.repository.seedDefaults()
        }
    }
}
