package io.personal.filemanager.ui

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Select TV-specific widgets by the actual device type, never by screen width. */
fun isTelevisionMode(uiMode: Int, hasLeanback: Boolean): Boolean =
    (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        hasLeanback

@Composable
fun isTelevisionUi(): Boolean {
    val uiMode = LocalConfiguration.current.uiMode
    val pm = LocalContext.current.packageManager
    return isTelevisionMode(uiMode, pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
}
