package io.personal.filemanager.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Independent navigation and layout policies for touchscreen and remote-controlled devices. */
enum class DeviceClass { PHONE, TABLET, TV }

fun resolveDeviceClass(uiMode: Int, smallestWidthDp: Int): DeviceClass = when {
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION -> DeviceClass.TV
    smallestWidthDp >= 600 -> DeviceClass.TABLET
    else -> DeviceClass.PHONE
}

@Composable
fun currentDeviceClass(): DeviceClass {
    val config = LocalConfiguration.current
    return resolveDeviceClass(config.uiMode, config.smallestScreenWidthDp)
}
