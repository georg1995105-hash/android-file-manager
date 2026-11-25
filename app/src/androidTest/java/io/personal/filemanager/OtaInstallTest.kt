package io.personal.filemanager

import android.content.pm.PackageInstaller
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.personal.filemanager.data.updates.OtaInstallSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the actual PackageInstaller callback and foreground confirmation on TV.
 * Only a disposable copy of this emulator's own debug APK is used; never commit user approval. */
@RunWith(AndroidJUnit4::class)
class OtaInstallTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun verifiedApkCanReachSystemConfirmationAndCancel() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = rule.activity
        val file = File(context.cacheDir, "ota-test.part.apk")
        try {
            File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            assertEquals(context.packageName, info?.packageName)
            assertTrue("Fixture must have install-source permission", context.packageManager.canRequestPackageInstalls())
            OtaInstallSession.start(context.applicationContext, file)
            val pending = runBlocking { withTimeout(30_000) { OtaInstallSession.events.filterNotNull().first() } }
            assertEquals(pending.message, PackageInstaller.STATUS_PENDING_USER_ACTION, pending.status)
            assertNotNull(pending.confirmation)
            instrument.runOnMainSync { context.startActivity(checkNotNull(pending.confirmation)) }
            val automation = instrument.uiAutomation
            var installerVisible = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val pkg = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
                if (pkg.contains("packageinstaller", ignoreCase = true) ||
                    pkg.contains("permissioncontroller", ignoreCase = true)) {
                    installerVisible = true
                    break
                }
                Thread.sleep(250)
            }
            assertTrue("Package installer confirmation did not become visible", installerVisible)
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
        } finally {
            OtaInstallSession.cancel(context.applicationContext)
            file.delete()
        }
    }
}
