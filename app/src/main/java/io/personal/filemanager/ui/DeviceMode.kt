package io.personal.filemanager.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Standard TV flags plus a hardware-based fallback for large touchless AOSP boxes. */
fun isTelevisionMode(uiMode: Int, hasLeanback: Boolean, hasTouchscreen: Boolean = true, largeLandscape: Boolean = false): Boolean {
    val type = uiMode and Configuration.UI_MODE_TYPE_MASK
    return type == Configuration.UI_MODE_TYPE_TELEVISION || hasLeanback ||
        (type == Configuration.UI_MODE_TYPE_NORMAL && !hasTouchscreen && largeLandscape)
}

@Composable
fun isTelevisionUi(): Boolean {
    val config = LocalConfiguration.current
    val pm = LocalContext.current.packageManager
    val tv = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        pm.hasSystemFeature("android.software.leanback_only") ||
        pm.hasSystemFeature("android.hardware.type.television")
    return isTelevisionMode(config.uiMode, tv, pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 700)
}

/** Keep the existing OAuth method on generic boxes that were previously using Google Play Services. */
@Composable
fun isStandardTelevisionUi(): Boolean {
    val config = LocalConfiguration.current
    val pm = LocalContext.current.packageManager
    return isTelevisionMode(config.uiMode, pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
}
