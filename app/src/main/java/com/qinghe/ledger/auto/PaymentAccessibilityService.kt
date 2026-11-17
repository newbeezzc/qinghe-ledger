package com.qinghe.ledger.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.qinghe.ledger.QingheApp
import com.qinghe.ledger.data.model.TransactionSource
import com.qinghe.ledger.util.AppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Optional, user-enabled reader for payment result and bill detail pages in
 * WeChat / Alipay. It never uploads anything.
 *
 * Text extraction is layered (no OCR):
 *  1. DFS over the active root, event source and all interactive windows.
 *  2. Targeted findAccessibilityNodeInfosByText + parent backtracking.
 *  3. Staggered retries while the WebView/RecyclerView is still rendering.
 */
class PaymentAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanJob: Job? = null
    private var lastSignature: String = ""
    private var lastRecordedAt: Long = 0L
    private var lastScanStartedAt: Long = 0L

    // A payment page can open while an earlier retry chain is still running.
    // Remember the newest event and rescan after the chain instead of dropping
    // it: WeChat often emits no further event once the bill page is drawn.
    private var pendingRescan: Boolean = false
    private var pendingSource: AccessibilityNodeInfo? = null
    private var pendingEventText: String = ""

    private data class ScanOutcome(
        val sawText: Boolean,
        val recorded: Boolean
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureDiagnostics.setAccessibilityConnected(true)
        // Make the capabilities explicit; some ROMs ignore parts of the XML.
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            serviceInfo = info
        }
        Log.d(TAG, "accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInterestingEvent(event.eventType)) return
        // Do not compete with the bookkeeping UI for the main thread.
        if (AppVisibility.isForeground) return

        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        val eventPackage = event.packageName?.toString()
        val rootPackage = runCatching { activeRoot?.packageName?.toString() }.getOrNull()
        val pkg = when {
            eventPackage != null && eventPackage in PaymentParser.supportedPackages -> eventPackage
            rootPackage != null && rootPackage in PaymentParser.supportedPackages -> rootPackage
            else -> return
        }

        // Queue the newest event instead of dropping it while a retry chain is
        // active; otherwise a bill page opened mid-chain could never be read.
        if (scanJob?.isActive == true) {
            pendingRescan = true
            pendingSource = runCatching { event.source }.getOrNull()
            pendingEventText = if (eventPackage == pkg) eventTextOf(event) else ""
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastScanStartedAt < SCAN_THROTTLE_MS) return
        lastScanStartedAt = now

        val firstSource = runCatching { event.source }.getOrNull()
        val firstEventText = if (eventPackage == pkg) eventTextOf(event) else ""
        startScan(pkg, firstSource, firstEventText)
    }

    private fun eventTextOf(event: AccessibilityEvent): String = buildList {
        event.text?.forEach { text ->
            text?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString(" ")

    private fun startScan(
        pkg: String,
        source: AccessibilityNodeInfo?,
        eventText: String
    ) {
        scanJob = scope.launch {
            val outcome = scanPage(pkg, source, eventText)
            if (outcome.recorded) {
                pendingRescan = false
                pendingSource = null
                pendingEventText = ""
                return@launch
            }

            if (pendingRescan) {
                val nextSource = pendingSource
                val nextText = pendingEventText
                pendingRescan = false
                pendingSource = null
                pendingEventText = ""
                delay(RESCAN_DELAY_MS)
                if (AppVisibility.isForeground) return@launch
                if (isPageStillRelevant(pkg)) {
                    scanPage(pkg, nextSource, nextText)
                }
                return@launch
            }

            if (!outcome.sawText) {
                // A WebView / RecyclerView bill page can take longer than the
                // retry chain to expose any text. Give it one slow follow-up.
                delay(SLOW_RETRY_MS)
                if (AppVisibility.isForeground) return@launch
                if (isPageStillRelevant(pkg)) {
                    scanPage(pkg, null, "")
                }
            }
        }
    }

    private suspend fun scanPage(
        pkg: String,
        firstSource: AccessibilityNodeInfo?,
        firstEventText: String
    ): ScanOutcome {
        var sawText = firstEventText.isNotBlank()
        for (index in RETRY_DELAYS.indices) {
            if (AppVisibility.isForeground) return ScanOutcome(sawText, false)
            if (index > 0) {
                delay(RETRY_DELAYS[index] - RETRY_DELAYS[index - 1])
            }
            if (!isPageStillRelevant(pkg)) return ScanOutcome(sawText, false)

            val roots = currentRoots(pkg, firstSource)
            val textBuilder = StringBuilder()
            if (index == 0 && firstEventText.isNotBlank()) {
                textBuilder.append(firstEventText).append('\n')
            }
            roots.forEach { addRootText(it, pkg, textBuilder) }
            var text = normalizeLines(textBuilder.toString()).take(MAX_SCREEN_TEXT)
            if (text.isNotBlank()) sawText = true
            var analysis = PaymentParser.analyzeScreen(pkg, text)
            var usedTargeted = false

            if (analysis.payment == null) {
                val targeted = collectTargetedText(roots)
                if (targeted.isNotBlank()) {
                    usedTargeted = true
                    text = normalizeLines("$text\n$targeted").take(MAX_SCREEN_TEXT)
                    analysis = PaymentParser.analyzeScreen(pkg, text)
                }
            }

            val parsed = analysis.payment
            if (parsed != null) {
                val signature = "$pkg|${text.hashCode()}"
                val now = System.currentTimeMillis()
                if (signature == lastSignature && now - lastRecordedAt < 60_000L) {
                    return ScanOutcome(true, true)
                }
                lastSignature = signature
                lastRecordedAt = now
                CaptureDiagnostics.recordScreen(
                    pkg = pkg,
                    text = text,
                    parsed = true,
                    reason = analysis.reason,
                    attempt = index + 1,
                    usedTargetedSearch = usedTargeted
                )
                Log.d(
                    TAG,
                    "$pkg ${analysis.reason} attempt=${index + 1} chars=${text.length} text=${text.take(300)}"
                )
                val app = application as? QingheApp ?: return ScanOutcome(sawText, false)
                app.container.autoRecorder.handle(
                    pkg = pkg,
                    parsed = parsed,
                    source = TransactionSource.ACCESSIBILITY
                )
                return ScanOutcome(true, true)
            }

            if (index == 0 || text.isNotBlank()) {
                CaptureDiagnostics.recordScreen(
                    pkg = pkg,
                    text = text,
                    parsed = false,
                    reason = analysis.reason,
                    attempt = index + 1,
                    usedTargetedSearch = usedTargeted
                )
                Log.d(
                    TAG,
                    "$pkg attempt=${index + 1} ${analysis.reason} chars=${text.length} text=${text.take(200)}"
                )
            }
        }
        return ScanOutcome(sawText, false)
    }

    private fun isPageStillRelevant(pkg: String): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root?.packageName?.toString() == pkg) return true
        return runCatching {
            windows?.any { it.root?.packageName?.toString() == pkg } == true
        }.getOrDefault(false)
    }

    private fun currentRoots(
        pkg: String,
        extra: AccessibilityNodeInfo? = null
    ): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()

        fun add(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val nodePackage = runCatching { node.packageName?.toString() }.getOrNull()
            if (nodePackage != null && nodePackage != pkg) return
            if (!seen.add(System.identityHashCode(node))) return
            roots.add(node)
        }

        add(runCatching { rootInActiveWindow }.getOrNull())
        add(extra)
        runCatching { windows?.forEach { add(it.root) } }
        return roots
    }

    private fun addRootText(
        root: AccessibilityNodeInfo?,
        pkg: String,
        out: StringBuilder
    ) {
        if (root == null) return
        val nodePackage = runCatching { root.packageName?.toString() }.getOrNull()
        if (nodePackage != null && nodePackage != pkg) return
        // Each window gets its own budget: a text-heavy chat list must not
        // consume a shared cap and hide the bill-detail window.
        val rootText = StringBuilder()
        collectVisibleText(root, rootText, maxNodes = 1600, maxChars = ROOT_TEXT_BUDGET)
        if (rootText.isNotEmpty()) {
            out.append(rootText)
            if (!rootText.endsWith("\n")) out.append('\n')
        }
    }

    /**
     * When the normal tree is huge or partly virtual, search the page for the
     * field labels that always exist on a bill detail page, then read the
     * matched node and its ancestors.
     */
    private fun collectTargetedText(roots: List<AccessibilityNodeInfo>): String {
        if (roots.isEmpty()) return ""
        val out = StringBuilder()
        for (root in roots) {
            runCatching { root.refresh() }
            for (keyword in TARGET_KEYWORDS) {
                if (out.length > 3500) break
                val nodes = runCatching {
                    root.findAccessibilityNodeInfosByText(keyword)
                }.getOrNull() ?: continue
                nodes.take(6).forEach { node ->
                    collectVisibleText(node, out, maxNodes = 100)
                    var parent = runCatching { node.parent }.getOrNull()
                    repeat(2) {
                        if (parent != null && out.length < 3500) {
                            collectVisibleText(parent, out, maxNodes = 100)
                            parent = runCatching { parent?.parent }.getOrNull()
                        }
                    }
                }
            }
        }
        return normalizeLines(out.toString())
    }

    private fun collectVisibleText(
        root: AccessibilityNodeInfo,
        out: StringBuilder,
        maxNodes: Int,
        maxChars: Int = ROOT_TEXT_BUDGET
    ) {
        runCatching { root.refresh() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes && out.length < maxChars) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                out.append(it).append('\n')
            }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                out.append(it).append('\n')
            }
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let {
                out.append(it).append('\n')
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.stateDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                    out.append(it).append('\n')
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                node.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let {
                    out.append(it).append('\n')
                }
            }
            for (childIndex in 0 until node.childCount) {
                node.getChild(childIndex)?.let { child -> queue.add(child) }
            }
        }
    }

    private fun normalizeLines(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")

    private fun isInterestingEvent(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_SELECTED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> true
        else -> false
    }

    override fun onInterrupt() {
        scanJob?.cancel()
        clearPendingScan()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        scanJob?.cancel()
        clearPendingScan()
        CaptureDiagnostics.setAccessibilityConnected(false)
        Log.d(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    private fun clearPendingScan() {
        pendingRescan = false
        pendingSource = null
        pendingEventText = ""
    }

    override fun onDestroy() {
        scanJob?.cancel()
        scope.cancel()
        CaptureDiagnostics.setAccessibilityConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QingheA11y"
        private const val SCAN_THROTTLE_MS = 400L
        private const val RESCAN_DELAY_MS = 250L
        private const val SLOW_RETRY_MS = 5_000L
        private const val MAX_SCREEN_TEXT = 20_000
        private const val ROOT_TEXT_BUDGET = 8_000
        private val RETRY_DELAYS = longArrayOf(0, 600, 1_500, 3_000, 5_000, 7_500)
        private val TARGET_KEYWORDS = listOf(
            "交易单号", "支付时间", "付款时间", "交易时间",
            "商户全称", "商户名称", "当前状态", "支付方式",
            "付款方式", "收款方", "订单号", "金额", "¥", "￥",
            "支付成功", "已完成", "账单详情", "订单详情", "完成"
        )
    }
}
