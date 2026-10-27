package com.qinghe.ledger.auto

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.qinghe.ledger.ui.QuickEditActivity
import com.qinghe.ledger.data.AutoRecordResult
import com.qinghe.ledger.util.MoneyFormat
import com.qinghe.ledger.util.formatDateTime

/**
 * Bottom overlay shown after an automatic record, similar to 一木记账.
 * Requires the "display over other apps" permission; falls back to the
 * high-priority notification when the permission is missing.
 */
object AutoRecordOverlay {
    private const val AUTO_DISMISS_MS = 5_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var dismissRunnable: Runnable? = null

    /**
     * Shows the overlay for every auto-recorded payment. Returns false when the
     * overlay permission is missing so the caller can fall back to a notification;
     * if adding the window fails asynchronously, [onUnavailable] is invoked.
     */
    fun show(
        context: Context,
        result: AutoRecordResult,
        onCancel: () -> Unit,
        onUnavailable: () -> Unit
    ): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val appContext = context.applicationContext
        mainHandler.post {
            val shown = runCatching {
                showInternal(appContext, result, onCancel)
            }.getOrDefault(false)
            if (!shown) onUnavailable()
        }
        return true
    }

    private fun showInternal(
        context: Context,
        result: AutoRecordResult,
        onCancel: () -> Unit
    ): Boolean {
        dismiss()
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        windowManager = manager

        val root = FrameLayout(context).apply {
            setPadding(dp(context, 16), 0, dp(context, 16), 0)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFCF6"))
                cornerRadius = dp(context, 22).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#E3DCCB"))
            }
            elevation = dp(context, 12).toFloat()
            setPadding(dp(context, 18), dp(context, 15), dp(context, 18), dp(context, 13))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "已自动记账"
            setTextColor(Color.parseColor("#2F6B4F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val close = TextView(context).apply {
            text = "×"
            setTextColor(Color.parseColor("#6B746D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            setOnClickListener { dismiss() }
        }
        header.addView(close)
        card.addView(header)

        val amount = TextView(context).apply {
            text = "¥${MoneyFormat.amount(result.amountCents)}"
            setTextColor(Color.parseColor("#1C2822"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, dp(context, 5), 0, 0)
        }
        card.addView(amount)

        val detail = TextView(context).apply {
            text = "${result.categoryName} · ${result.accountName} · ${formatDateTime(result.occurredAt)}"
            setTextColor(Color.parseColor("#5D665F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        card.addView(detail)

        if (result.note.isNotBlank()) {
            val note = TextView(context).apply {
                text = result.note
                setTextColor(Color.parseColor("#5D665F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(context, 3), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            card.addView(note)
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 12), 0, 0)
        }
        val cancel = TextView(context).apply {
            text = "取消记账"
            setTextColor(Color.parseColor("#B3261E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            setOnClickListener {
                dismiss()
                onCancel()
            }
        }
        buttons.addView(cancel)

        val later = TextView(context).apply {
            text = "知道了"
            setTextColor(Color.parseColor("#5D665F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(context, 14), dp(context, 9), dp(context, 14), dp(context, 9))
            setOnClickListener { dismiss() }
        }
        buttons.addView(later)

        val edit = TextView(context).apply {
            text = "修改"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2F6B4F"))
                cornerRadius = dp(context, 14).toFloat()
            }
            setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
            setOnClickListener {
                dismiss()
                val intent = Intent(context, QuickEditActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(QuickEditActivity.EXTRA_TRANSACTION_ID, result.transactionId)
                }
                runCatching { context.startActivity(intent) }
            }
        }
        buttons.addView(
            edit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(context, 8) }
        )
        card.addView(buttons)

        root.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = dp(context, 84)
        }

        return runCatching {
            manager.addView(root, params)
            overlayView = root
            val runnable = Runnable { dismiss() }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, AUTO_DISMISS_MS)
            true
        }.getOrElse {
            overlayView = null
            windowManager = null
            false
        }
    }

    private fun dismiss() {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        windowManager = null
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
