package com.qinghe.ledger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.auto.AutoRecordEvents
import com.qinghe.ledger.auto.PaymentType
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.AccountType
import com.qinghe.ledger.ui.screens.AccountManagerScreen
import com.qinghe.ledger.ui.screens.AddTransactionScreen
import com.qinghe.ledger.ui.screens.BudgetScreen
import com.qinghe.ledger.ui.screens.CategoryManagerScreen
import com.qinghe.ledger.ui.screens.HomeScreen
import com.qinghe.ledger.ui.screens.RulesScreen
import com.qinghe.ledger.ui.screens.SettingsScreen
import com.qinghe.ledger.ui.screens.StatsScreen
import com.qinghe.ledger.ui.screens.UpdateDialog
import com.qinghe.ledger.update.UpdateCheckResult
import com.qinghe.ledger.update.UpdateInfo
import com.qinghe.ledger.update.UpdateInstallResult
import com.qinghe.ledger.update.UpdateManager
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime
import com.qinghe.ledger.util.openAppNotificationSettings
import com.qinghe.ledger.util.openOverlaySettings
import com.qinghe.ledger.util.rememberAutoPermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private data class TabItem(
    val label: String,
    val icon: ImageVector
)

private sealed interface AddTarget {
    data object New : AddTarget
    data class Edit(val transaction: TransactionEntity) : AddTarget
}

@Composable
fun LedgerRoot(editTransactionId: MutableStateFlow<Long?>? = null) {
    val context = LocalContext.current
    val application = context.applicationContext as QingheApp
    val viewModel: LedgerViewModel =
        viewModel(factory = LedgerViewModel.factory(application))

    val fallbackEditId = remember { MutableStateFlow<Long?>(null) }
    val editIdFlow = editTransactionId ?: fallbackEditId
    val requestedEditId by editIdFlow.collectAsStateWithLifecycle()

    val month by viewModel.selectedMonth.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val balances by viewModel.accountBalances.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val latestAuto by viewModel.latestAutoTransaction.collectAsStateWithLifecycle()
    val permissions = rememberAutoPermissionState()

    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    val accountMap = remember(accounts) { accounts.associateBy { it.id } }
    val latestAutoCategoryPath = remember(latestAuto, categoryMap) {
        val category = latestAuto?.categoryId?.let { categoryMap[it] }
        val parent = category?.parentId?.let { categoryMap[it] }
        when {
            parent != null && category != null -> "${parent.name}-${category.name}"
            category != null -> category.name
            else -> null
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var showAccounts by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }

    val checkForUpdates: (Boolean) -> Unit = { manual ->
        if (checkingUpdate) {
            if (manual) {
                scope.launch {
                    snackbarHostState.showSnackbar("正在检查更新，请稍候")
                }
            }
        } else {
            val repo = settings.githubRepo
            if (repo.isBlank()) {
                if (manual) {
                    scope.launch {
                        snackbarHostState.showSnackbar("请先在「我的 → GitHub 更新源」配置仓库")
                    }
                }
            } else {
                checkingUpdate = true
                scope.launch {
                    try {
                        when (val result = UpdateManager.check(repo)) {
                            is UpdateCheckResult.Available -> {
                                updateInfo = result.info
                                updateError = null
                            }
                            UpdateCheckResult.UpToDate -> {
                                if (manual) {
                                    snackbarHostState.showSnackbar("已经是最新版本")
                                }
                            }
                            is UpdateCheckResult.Failure -> {
                                if (manual) {
                                    snackbarHostState.showSnackbar(result.message)
                                }
                            }
                        }
                    } finally {
                        checkingUpdate = false
                        viewModel.markUpdateChecked()
                    }
                }
            }
        }
    }

    val startUpdateDownload: () -> Unit = {
        val info = updateInfo
        if (info != null && !downloadingUpdate) {
            downloadingUpdate = true
            updateError = null
            scope.launch {
                try {
                    val file = UpdateManager.download(context, info)
                    val validation = UpdateManager.validateApk(context, file)
                    if (validation != null) {
                        updateError = validation
                    } else {
                        when (val result = UpdateManager.install(context, file)) {
                            UpdateInstallResult.Started -> {
                                updateInfo = null
                                updateError = null
                            }
                            is UpdateInstallResult.NeedsPermission -> {
                                updateError = result.message
                            }
                            is UpdateInstallResult.Failure -> {
                                updateError = result.message
                            }
                        }
                    }
                } catch (error: Exception) {
                    updateError = error.message ?: "下载更新失败"
                } finally {
                    downloadingUpdate = false
                }
            }
        }
    }

    LaunchedEffect(settings.githubRepo, settings.autoCheckUpdates) {
        if (settings.githubRepo.isNotBlank() &&
            settings.autoCheckUpdates &&
            System.currentTimeMillis() - settings.lastUpdateCheck > 24L * 60L * 60L * 1000L &&
            updateInfo == null
        ) {
            checkForUpdates(false)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        AutoRecordEvents.events.collect { result ->
            val typeLabel = if (result.type == PaymentType.INCOME) "收入" else "支出"
            snackbarHostState.showSnackbar(
                "已自动记账 · $typeLabel ${MoneyFormat.withSymbol(result.amountCents)} · " +
                    "${result.categoryName}（${formatDateTime(result.occurredAt)}）"
            )
        }
    }

    LaunchedEffect(Unit) {
        AutoRecordEvents.cancelled.collect { result ->
            snackbarHostState.showSnackbar(
                "已取消这次自动记账 · ${MoneyFormat.withSymbol(result.amountCents)}"
            )
        }
    }

    LaunchedEffect(requestedEditId) {
        val id = requestedEditId ?: return@LaunchedEffect
        val transaction = viewModel.findTransaction(id)
        if (transaction != null) {
            addTarget = AddTarget.Edit(transaction)
        }
        editIdFlow.value = null
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val overlayVisible = addTarget != null || showAccounts || showCategories || showRules
    BackHandler(enabled = overlayVisible) {
        addTarget = null
        showAccounts = false
        showCategories = false
        showRules = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val tabs = listOf(
                        TabItem("明细", Icons.Rounded.ReceiptLong),
                        TabItem("统计", Icons.Rounded.PieChart),
                        TabItem("预算", Icons.Rounded.Savings),
                        TabItem("我的", Icons.Rounded.Person)
                    )
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            },
            floatingActionButton = {
                if (!overlayVisible && tab != 3) {
                    FloatingActionButton(
                        onClick = { addTarget = AddTarget.New },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "记一笔")
                    }
                }
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when (tab) {
                    0 -> HomeScreen(
                        month = month,
                        summary = summary,
                        transactions = transactions,
                        categories = categoryMap,
                        accounts = accountMap,
                        budget = budget,
                        permissions = permissions,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onOpenAdd = { addTarget = AddTarget.New },
                        onEdit = { addTarget = AddTarget.Edit(it) },
                        onDelete = viewModel::deleteTransaction,
                        onOpenAutoSettings = { tab = 3 },
                        onOpenNotificationSettings = { openAppNotificationSettings(context) },
                        onOpenOverlaySettings = { openOverlaySettings(context) }
                    )
                    1 -> StatsScreen(
                        month = month,
                        transactions = transactions,
                        categories = categoryMap,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth
                    )
                    2 -> BudgetScreen(
                        month = month,
                        budget = budget,
                        summary = summary,
                        transactions = transactions,
                        categories = categoryMap,
                        onPreviousMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onSetBudget = { cents -> viewModel.setBudget(month, cents) }
                    )
                    else -> SettingsScreen(
                        settings = settings,
                        latestAuto = latestAuto,
                        latestAutoCategoryPath = latestAutoCategoryPath,
                        onToggleNotificationAuto = viewModel::setNotificationAutoEnabled,
                        onToggleAccessibilityAuto = viewModel::setAccessibilityAutoEnabled,
                        onToggleShowAutoNotification = viewModel::setShowAutoNotification,
                        onOpenAccounts = { showAccounts = true },
                        onOpenCategories = { showCategories = true },
                        onOpenRules = { showRules = true },
                        onCheckUpdate = { checkForUpdates(true) },
                        onSetGithubRepo = viewModel::setGithubRepo,
                        onToggleAutoCheckUpdate = viewModel::setAutoCheckUpdates
                    )
                }
            }
        }

        addTarget?.let { target ->
            val existing = (target as? AddTarget.Edit)?.transaction
            AddTransactionScreen(
                existing = existing,
                categories = categories,
                accounts = accounts,
                accountBalances = balances,
                defaultAccountId = accounts.firstOrNull { it.type == AccountType.CASH.name }?.id
                    ?: accounts.firstOrNull()?.id,
                onDismiss = { addTarget = null },
                onSave = { transaction ->
                    viewModel.saveTransaction(transaction, previous = existing)
                    addTarget = null
                },
                onDelete = { transaction ->
                    viewModel.deleteTransaction(transaction)
                    addTarget = null
                },
                onValidationError = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            )
        }

        if (showAccounts) {
            AccountManagerScreen(
                accounts = accounts,
                balances = balances,
                onBack = { showAccounts = false },
                onAdd = viewModel::addAccount,
                onUpdate = viewModel::updateAccount,
                onDelete = viewModel::deleteAccount
            )
        }

        if (showCategories) {
            CategoryManagerScreen(
                categories = categories,
                onBack = { showCategories = false },
                onAdd = viewModel::addCategory,
                onUpdate = viewModel::updateCategory,
                onDelete = viewModel::deleteCategory
            )
        }

        if (showRules) {
            RulesScreen(
                rules = rules,
                categories = categories,
                onBack = { showRules = false },
                onAdd = viewModel::addRule,
                onDelete = viewModel::deleteRule
            )
        }

        updateInfo?.let { info ->
            UpdateDialog(
                info = info,
                downloading = downloadingUpdate,
                error = updateError,
                onDismiss = {
                    if (!downloadingUpdate) {
                        updateInfo = null
                        updateError = null
                    }
                },
                onDownload = startUpdateDownload
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
