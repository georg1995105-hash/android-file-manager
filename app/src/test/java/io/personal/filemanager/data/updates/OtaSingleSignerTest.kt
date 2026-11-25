package io.personal.filemanager.data.updates

import org.junit.Assert.*
import org.junit.Test

class OtaSingleSignerTest {
    @Test fun modernSignerWinsWithoutFallback() {
        assertEquals("production", otaSingleSigner(arrayOf("production")) { error("unexpected legacy query") })
    }

    @Test fun missingModernSignerUsesLegacy() {
        assertEquals("production", otaSingleSigner<String>(null) { arrayOf("production") })
        assertEquals("production", otaSingleSigner(emptyArray<String>()) { arrayOf("production") })
    }

    @Test fun explicitMultipleSignersCannotFallBack() {
        assertNull(otaSingleSigner(arrayOf("first", "second")) { error("unexpected legacy query") })
    }

    @Test fun explicitDifferentSignerIsNotReplaced() {
        assertEquals("different", otaSingleSigner(arrayOf("different")) { error("unexpected legacy query") })
    }

    @Test fun missingOrAmbiguousLegacySignerFailsClosed() {
        assertNull(otaSingleSigner<String>(null) { null })
        assertNull(otaSingleSigner<String>(null) { emptyArray() })
        assertNull(otaSingleSigner<String>(null) { arrayOf("first", "second") })
    }
}
