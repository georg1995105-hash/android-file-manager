package io.personal.filemanager.ui

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModeTest {
    @Test fun phonesAndTabletsRetainOriginalTouchUi() {
        assertFalse(isTelevisionMode(Configuration.UI_MODE_TYPE_NORMAL, false))
        assertFalse(isTelevisionMode(Configuration.UI_MODE_TYPE_DESK, false))
        assertFalse(isTelevisionMode(Configuration.UI_MODE_TYPE_NORMAL, false, true, true))
        assertFalse(isTelevisionMode(Configuration.UI_MODE_TYPE_NORMAL, false, false, false))
        assertFalse(isTelevisionMode(Configuration.UI_MODE_TYPE_DESK, false, false, true))
    }
    @Test fun televisionAndLeanbackBoxesUseRemoteUi() {
        assertTrue(isTelevisionMode(Configuration.UI_MODE_TYPE_TELEVISION, false))
        assertTrue(isTelevisionMode(Configuration.UI_MODE_TYPE_NORMAL, true))
    }
    @Test fun genericAospBoxWithoutTouchscreenUsesRemoteUi() {
        assertTrue(isTelevisionMode(Configuration.UI_MODE_TYPE_NORMAL, false, false, true))
    }
}
