package io.personal.filemanager.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClassTest {
    @Test fun phonePortraitAndLandscapeStayTouchUi() {
        assertEquals(DeviceClass.PHONE, resolveDeviceClass(Configuration.UI_MODE_TYPE_NORMAL, 393))
        assertEquals(DeviceClass.PHONE, resolveDeviceClass(Configuration.UI_MODE_TYPE_NORMAL, 411))
    }
    @Test fun largeTabletUsesTouchUi() {
        assertEquals(DeviceClass.TABLET, resolveDeviceClass(Configuration.UI_MODE_TYPE_NORMAL, 600))
        assertEquals(DeviceClass.TABLET, resolveDeviceClass(Configuration.UI_MODE_TYPE_NORMAL, 840))
    }
    @Test fun televisionAlwaysUsesRemoteUi() {
        assertEquals(DeviceClass.TV, resolveDeviceClass(Configuration.UI_MODE_TYPE_TELEVISION, 320))
        assertEquals(DeviceClass.TV, resolveDeviceClass(Configuration.UI_MODE_TYPE_TELEVISION, 960))
    }
}
