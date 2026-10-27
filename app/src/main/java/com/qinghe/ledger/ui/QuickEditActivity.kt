package com.qinghe.ledger.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.theme.QingheTheme
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent, dialog-only activity used by the auto-record overlay. It edits
 * the just-recorded transaction in a popup and finishes itself after saving,
 * returning the user to WeChat/Alipay without opening the main app.
 */
class QuickEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val transactionId = intent.getLongExtra(EXTRA_TRANSACTION_ID, 0L)
        if (transactionId <= 0L) {
            finish()
            return
        }
        setContent {
            QingheTheme {
                QuickEditHost(
                    transactionId = transactionId,
                    onDismiss = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_TRANSACTION_ID = "quick_edit_transaction_id"
    }
}

@Composable
private fun QuickEditHost(
    transactionId: Long,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as QingheApp).container.repository
    }
    val scope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<TransactionEntity?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<AccountEntity>>(emptyList()) }

    LaunchedEffect(transactionId) {
        val loaded = repository.findTransaction(transactionId)
        if (loaded == null) {
            Toast.makeText(context, "这笔账目已不存在", Toast.LENGTH_SHORT).show()
            onDismiss()
        } else {
            // Load categories/accounts first so the dialog can show the
            // existing category on its first composition.
            categories = repository.categories.first()
            accounts = repository.accounts.first()
            transaction = loaded
        }
    }

    val current = transaction
    if (current == null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("加载中…")
                }
            }
        }
        return
    }

    QuickEditDialog(
        transaction = current,
        categories = categories,
        accounts = accounts,
        onDismiss = onDismiss,
        onSave = { updated ->
            scope.launch {
                runCatching {
                    repository.updateTransaction(updated)
                    repository.learnCategoryRule(current, updated)
                }.onSuccess {
                    Toast.makeText(context, "已保存修改", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }.onFailure {
                    Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun QuickEditDialog(
    transaction: TransactionEntity,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (TransactionEntity) -> Unit
) {
    val initialType = TransactionType.from(transaction.type).let {
        if (it == TransactionType.TRANSFER) TransactionType.EXPENSE else it
    }
    var type by remember(transaction.id) { mutableStateOf(initialType) }
    var amountInput by remember(transaction.id) {
        mutableStateOf(MoneyFormat.centsToInput(transaction.amountCents))
    }
    var categoryId by remember(transaction.id) { mutableStateOf(transaction.categoryId) }
    var accountId by remember(transaction.id) { mutableStateOf(transaction.accountId) }
    var note by remember(transaction.id) { mutableStateOf(transaction.note) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }

    val typeCategories = categories.filter { it.type == type.name }
    val categoryById = categories.associateBy { it.id }
    val topLevel = typeCategories.filter { it.parentId == null }
    val childrenByParent = typeCategories.groupBy { it.parentId }
    // Quick edit offers concrete categories only: subcategories where they
    // exist, otherwise the top-level category. This keeps the popup compact.
    val categoryOptions = buildList {
        topLevel.forEach { parent ->
            val children = childrenByParent[parent.id].orEmpty()
            if (children.isEmpty()) add(parent) else addAll(children)
        }
    }
    val selectedCategory = categoryOptions.firstOrNull { it.id == categoryId }
    val selectedPath = selectedCategory?.let { category ->
        val parent = category.parentId?.let { categoryById[it] }
        if (parent != null) "${parent.name}-${category.name}" else category.name
    }
    val amountCents = MoneyFormat.inputToCents(amountInput)
    val valid = (amountCents ?: 0L) > 0L && selectedCategory != null && accountId > 0L

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dialogMaxHeight = maxHeight * 0.88f
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = dialogMaxHeight)
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "修改自动记账",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }

                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { input ->
                            if (input.length <= 12 && input.all { it.isDigit() || it == '.' }) {
                                amountInput = input
                            }
                        },
                        label = { Text("金额") },
                        leadingIcon = { Text("¥") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = amountCents == null || amountCents <= 0L,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "类型",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == TransactionType.EXPENSE,
                            onClick = {
                                if (type != TransactionType.EXPENSE) {
                                    type = TransactionType.EXPENSE
                                    categoryId = null
                                }
                            },
                            label = { Text("支出") }
                        )
                        FilterChip(
                            selected = type == TransactionType.INCOME,
                            onClick = {
                                if (type != TransactionType.INCOME) {
                                    type = TransactionType.INCOME
                                    categoryId = null
                                }
                            },
                            label = { Text("收入") }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = categoryMenuExpanded,
                        onExpandedChange = { categoryMenuExpanded = !categoryMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory?.let { "${it.emoji} $selectedPath" }
                                ?: "请选择分类",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("分类") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = categoryMenuExpanded
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            categoryOptions.forEach { option ->
                                val parent = option.parentId?.let { categoryById[it] }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (parent != null) {
                                                "${option.emoji} ${parent.name}-${option.name}"
                                            } else {
                                                "${option.emoji} ${option.name}"
                                            }
                                        )
                                    },
                                    onClick = {
                                        categoryId = option.id
                                        categoryMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    if (categoryId == null) {
                        Text(
                            text = "请选择分类",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "账户",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        accounts.forEach { account ->
                            FilterChip(
                                selected = account.id == accountId,
                                onClick = { accountId = account.id },
                                label = { Text(account.name) }
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { if (it.length <= 80) note = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "支付时间：${formatDateTime(transaction.occurredAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = valid,
                            onClick = {
                                val cents = amountCents
                                val category = selectedCategory
                                if (cents != null && category != null) {
                                    onSave(
                                        transaction.copy(
                                            type = type.name,
                                            amountCents = cents,
                                            categoryId = category.id,
                                            accountId = accountId,
                                            note = note.trim()
                                        )
                                    )
                                }
                            }
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}
