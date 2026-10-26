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

        // Let the current retry chain finish before starting another one.
        if (scanJob?.isActive == true) return
        val now = System.currentTimeMillis()
        if (now - lastScanStartedAt < SCAN_THROTTLE_MS) return
        lastScanStartedAt = now

        val firstSource = runCatching { event.source }.getOrNull()
        val firstEventText = if (eventPackage == pkg) {
            event.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
        } else {
            ""
        }
        scanJob = scope.launch {
            scanPage(pkg, firstSource, firstEventText)
        }
    }

    private suspend fun scanPage(
        pkg: String,
        firstSource: AccessibilityNodeInfo?,
        firstEventText: String
    ) {
        for (index in RETRY_DELAYS.indices) {
            if (AppVisibility.isForeground) return
            if (index > 0) {
                delay(RETRY_DELAYS[index] - RETRY_DELAYS[index - 1])
            }
            if (!isPageStillRelevant(pkg)) return

            val roots = currentRoots(pkg)
            val textBuilder = StringBuilder()
            if (index == 0) {
                if (firstEventText.isNotBlank()) {
                    textBuilder.append(firstEventText).append('\n')
                }
                addRootText(firstSource, pkg, textBuilder)
            }
            roots.forEach { addRootText(it, pkg, textBuilder) }
            var text = normalizeLines(textBuilder.toString())
            var analysis = PaymentParser.analyzeScreen(pkg, text)
            var usedTargeted = false

            if (analysis.payment == null) {
                val targeted = collectTargetedText(roots)
                if (targeted.isNotBlank()) {
                    usedTargeted = true
                    text = normalizeLines("$text\n$targeted")
                    analysis = PaymentParser.analyzeScreen(pkg, text)
                }
            }

            val parsed = analysis.payment
            if (parsed != null) {
                val signature = "$pkg|${text.hashCode()}"
                val now = System.currentTimeMillis()
                if (signature == lastSignature && now - lastRecordedAt < 60_000L) return
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
                val app = application as? QingheApp ?: return
                app.container.autoRecorder.handle(
                    pkg = pkg,
                    parsed = parsed,
                    source = TransactionSource.ACCESSIBILITY
                )
                return
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
    }

    private fun isPageStillRelevant(pkg: String): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root?.packageName?.toString() == pkg) return true
        return runCatching {
            windows?.any { it.root?.packageName?.toString() == pkg } == true
        }.getOrDefault(false)
    }

    private fun currentRoots(pkg: String): List<AccessibilityNodeInfo> {
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
        collectVisibleText(root, out, maxNodes = 1200)
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
        maxNodes: Int
    ) {
        runCatching { root.refresh() }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes && out.length < 6000) {
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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        scanJob?.cancel()
        CaptureDiagnostics.setAccessibilityConnected(false)
        Log.d(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
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
        private val RETRY_DELAYS = longArrayOf(0, 800, 2000, 3500)
        private val TARGET_KEYWORDS = listOf(
            "交易单号", "支付时间", "付款时间", "交易时间",
            "商户全称", "商户名称", "当前状态", "支付方式",
            "付款方式", "收款方", "订单号", "金额", "¥", "￥"
        )
    }
}
