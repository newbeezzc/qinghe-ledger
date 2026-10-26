package com.qinghe.ledger.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qinghe.ledger.auto.PaymentAccessibilityService
import com.qinghe.ledger.auto.PaymentNotificationListener

data class AutoPermissionState(
    val notificationListener: Boolean,
    val accessibility: Boolean,
    val systemNotifications: Boolean,
    val overlay: Boolean
) {
    val autoReady: Boolean get() = notificationListener && accessibility
}

fun readAutoPermissionState(context: Context): AutoPermissionState {
    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
    return AutoPermissionState(
        notificationListener = enabledListeners.contains(context.packageName),
        accessibility = isAccessibilityServiceEnabled(context),
        systemNotifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        overlay = Settings.canDrawOverlays(context)
    )
}

/**
 * Different OEM ROMs persist the enabled service either as a fully qualified
 * class name or as a short name (".auto.PaymentAccessibilityService"). Ask
 * AccessibilityManager first and fall back to a normalised settings parse.
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, PaymentAccessibilityService::class.java)
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (manager != null) {
        val enabled = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        }.getOrDefault(emptyList())
        if (enabled.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo != null &&
                    serviceInfo.packageName == expected.packageName &&
                    classNamesMatch(
                        serviceInfo.name,
                        expected.className,
                        expected.packageName
                    )
            }
        ) {
            return true
        }
    }

    val raw = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    }.getOrNull().orEmpty()
    if (raw.isBlank()) return false

    return raw.split(':').any { entry ->
        val component = ComponentName.unflattenFromString(entry) ?: return@any false
        component.packageName == expected.packageName &&
            classNamesMatch(component.className, expected.className, expected.packageName)
    }
}

private fun classNamesMatch(actual: String?, expected: String, pkg: String): Boolean {
    if (actual == null) return false
    val normalised = when {
        actual.startsWith(".") -> pkg + actual
        !actual.contains('.') -> "$pkg.$actual"
        else -> actual
    }
    return normalised == expected
}

@Composable
fun rememberAutoPermissionState(): AutoPermissionState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(readAutoPermissionState(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = readAutoPermissionState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

fun openNotificationListenerSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}

fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { openAppSettings(context) }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
