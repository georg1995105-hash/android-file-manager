package io.personal.filemanager.data
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
class BrowserHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun restoresOnlyAnExistingFolderWithinRoot() {
        val root = tmp.newFolder("root")
        val nested = File(root, "nested").apply { mkdir() }
        val outside = tmp.newFolder("root-other")
        assertEquals(nested.canonicalPath, BrowserHistory.safeRestoredPath(root.path, nested.path))
        assertEquals(root.path, BrowserHistory.safeRestoredPath(root.path, outside.path))
        assertEquals(root.path, BrowserHistory.safeRestoredPath(root.path, File(root,"missing").path))
        val link = File(root,"link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(root.path, BrowserHistory.safeRestoredPath(root.path, link.path))
    }
}
