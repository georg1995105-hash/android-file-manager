package io.personal.filemanager.data.drive
import org.junit.Assert.*
import org.junit.Test
class DriveApiTest {
    @Test fun offsetsAndOrigins() {
        assertEquals(0L,DriveApi.nextOffset(null,100))
        assertEquals(50L,DriveApi.nextOffset("bytes=0-49",100))
        assertTrue(runCatching{DriveApi.nextOffset("bytes=0-100",100)}.isFailure)
        assertTrue(DriveApi.allowedSession("https://www.googleapis.com/upload/drive/v3/files?upload_id=x"))
        assertFalse(DriveApi.allowedSession("https://www.googleapis.com.evil/upload/drive/v3/files"))
        assertFalse(DriveApi.allowedSession("https://www.googleapis.com:8443/upload/drive/v3/files"))
        assertTrue(runCatching{DriveApi.validName("../x")}.isFailure)
        assertEquals("Видео.mp4",DriveApi.validName("Видео.mp4"))
    }
}
