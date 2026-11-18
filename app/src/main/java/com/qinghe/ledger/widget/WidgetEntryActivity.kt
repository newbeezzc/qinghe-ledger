package com.qinghe.ledger.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.QuickEntryResult
import com.qinghe.ledger.data.QuickTextParser
import com.qinghe.ledger.data.local.AccountEntity
import com.qinghe.ledger.data.local.CategoryEntity
import com.qinghe.ledger.data.local.TransactionEntity
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.data.model.TransactionType
import com.qinghe.ledger.ui.theme.IncomeGreen
import com.qinghe.ledger.ui.theme.QingheTheme
import com.qinghe.ledger.util.AppVisibility
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent bottom card opened by the home-screen widget. The user types one
 * line, the app parses amount/direction/date/category, and confirm inserts a
 * MANUAL transaction to the cash account.
 */
class WidgetEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            QingheTheme {
                WidgetEntryHost(onFinish = { finish() })
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
}

@Composable
private fun WidgetEntryHost(onFinish: () -> Unit) {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as QingheApp).container.repository
    }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var text by rememberSaveable { mutableStateOf("") }
    var parse by remember { mutableStateOf<QuickEntryResult?>(null) }
    var selectedType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var typeOverride by remember { mutableStateOf<TransactionType?>(null) }
    var selectedCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var cashAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            repository.seedDefaults()
            val loadedCategories = repository.categories.first()
            val account = repository.ensureCashAccount()
            loadedCategories to account
        }.onSuccess { (loadedCategories, account) ->
            categories = loadedCategories
            cashAccount = account
            if (loadedCategories.isEmpty()) {
                loadError = "分类尚未准备好，请稍后重试"
            }
        }.onFailure {
            loadError = "初始化失败，请重试"
        }
        loading = false
        delay(160)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    LaunchedEffect(text, typeOverride, loading) {
        if (loading) return@LaunchedEffect
        if (text.isBlank()) {
            parse = null
            return@LaunchedEffect
        }
        delay(250)
        val result = runCatching {
            repository.parseQuickEntry(text, typeOverride)
        }.getOrNull()
        parse = result ?: QuickEntryResult(
            amountCents = null,
            type = selectedType,
            occurredAtMillis = System.currentTimeMillis(),
            category = null,
            note = text,
            reason = "解析失败，请重试"
        )
        result?.let {
            selectedType = it.type
            selectedCategory = it.category
        }
    }

    val categoryPath = selectedCategory?.let { category ->
        val parent = category.parentId?.let { id -> categories.firstOrNull { it.id == id } }
        if (parent != null) "${parent.name}-${category.name}" else category.name
    }
    val selectedIsLeaf = selectedCategory?.let { category ->
        categories.none { it.parentId == category.id }
    } == true
    val selectedMatchesType = selectedCategory?.type == selectedType.name
    val canSave = parse?.amountCents != null &&
        selectedIsLeaf &&
        selectedMatchesType &&
        cashAccount != null &&
        !loading &&
        loadError == null &&
        !saving

    fun save() {
        val current = parse ?: return
        val amount = current.amountCents ?: return
        val category = selectedCategory ?: return
        val account = cashAccount ?: return
        if (saving || category.type != selectedType.name) return
        saving = true
        // Recompute the relative date so a card left open across midnight is correct.
        val occurredAt = QuickTextParser.parse(text).occurredAtMillis
        scope.launch {
            runCatching {
                repository.addTransaction(
                    TransactionEntity(
                        type = selectedType.name,
                        amountCents = amount,
                        categoryId = category.id,
                        accountId = account.id,
                        note = text.trim().take(80),
                        occurredAt = occurredAt,
                        source = TransactionSource.MANUAL.name
                    )
                )
            }.onSuccess {
                Toast.makeText(context, "已记一笔", Toast.LENGTH_SHORT).show()
                onFinish()
            }.onFailure {
                saving = false
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .padding(bottom = 10.dp)
                .heightIn(max = maxHeight * 0.88f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "快捷记一笔",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onFinish) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        typeOverride = null
                        parse = null
                        selectedCategory = null
                    },
                    label = { Text("一句话记账") },
                    placeholder = {
                        Text(
                            text = "例如：午饭 25、打车18.5、工资 8000",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        loading -> Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在准备…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        loadError != null -> Text(
                            text = loadError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        text.isBlank() -> Text(
                            text = "输入一句话，自动识别金额和分类",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        parse == null -> Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("识别中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        parse?.amountCents == null -> Text(
                            text = parse?.reason ?: "未找到金额",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        else -> QuickEntryPreview(
                            parse = requireNotNull(parse),
                            selectedType = selectedType,
                            categoryPath = categoryPath,
                            accountName = cashAccount?.name ?: "现金",
                            onTypeSelected = { type ->
                                if (type != selectedType) {
                                    selectedType = type
                                    selectedCategory = null
                                    typeOverride = type
                                    showCategoryPicker = false
                                }
                            },
                            onPickCategory = { showCategoryPicker = true }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onFinish) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { save() },
                        enabled = canSave
                    ) {
                        Text(if (saving) "保存中…" else "确认记账")
                    }
                }
            }
        }
    }

    if (showCategoryPicker) {
        CategoryPickerDialog(
            categories = categories,
            type = selectedType,
            selectedId = selectedCategory?.id,
            onDismiss = { showCategoryPicker = false },
            onSelect = { category ->
                selectedCategory = category
                showCategoryPicker = false
            }
        )
    }
}

@Composable
private fun QuickEntryPreview(
    parse: QuickEntryResult,
    selectedType: TransactionType,
    categoryPath: String?,
    accountName: String,
    onTypeSelected: (TransactionType) -> Unit,
    onPickCategory: () -> Unit
) {
    val amount = parse.amountCents ?: return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = MoneyFormat.withSymbol(amount),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selectedType == TransactionType.INCOME) {
                        IncomeGreen
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedType == TransactionType.EXPENSE,
                    onClick = { onTypeSelected(TransactionType.EXPENSE) },
                    label = { Text("支出") }
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = selectedType == TransactionType.INCOME,
                    onClick = { onTypeSelected(TransactionType.INCOME) },
                    label = { Text("收入") }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onPickCategory,
                    label = { Text(categoryPath ?: "选择分类") }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "账户：$accountName",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatDateTime(parse.occurredAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPickerDialog(
    categories: List<CategoryEntity>,
    type: TransactionType,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onSelect: (CategoryEntity) -> Unit
) {
    val typed = remember(categories, type) {
        categories.filter { it.type == type.name }
    }
    val topLevel = remember(typed) { typed.filter { it.parentId == null } }
    val childrenByParent = remember(typed) { typed.groupBy { it.parentId } }
    val selected = typed.firstOrNull { it.id == selectedId }
    var parentId by remember(typed, selectedId) {
        mutableStateOf(selected?.parentId ?: selected?.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择分类") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    topLevel.forEach { category ->
                        val children = childrenByParent[category.id].orEmpty()
                        FilterChip(
                            selected = parentId == category.id,
                            onClick = {
                                parentId = category.id
                                if (children.isEmpty()) onSelect(category)
                            },
                            label = { Text("${category.emoji} ${category.name}") }
                        )
                    }
                }

                val children = childrenByParent[parentId].orEmpty()
                if (children.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "小类",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        children.forEach { child ->
                            FilterChip(
                                selected = child.id == selectedId,
                                onClick = { onSelect(child) },
                                label = { Text("${child.emoji} ${child.name}") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
