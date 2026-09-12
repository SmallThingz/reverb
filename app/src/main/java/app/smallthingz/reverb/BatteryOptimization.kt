package app.smallthingz.reverb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri

@SuppressLint("BatteryLife")
fun openBatteryOptimizationReview(context: Context): Boolean {
    val intents = buildList {
        if (!isIgnoringBatteryOptimizations(context)) {
            add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:${context.packageName}".toUri()
            })
        }
        add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
            data = "package:${context.packageName}".toUri()
            putExtra("package_name", context.packageName)
            putExtra("packageName", context.packageName)
        })
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        })
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }
    return intents.any { intent ->
        runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
