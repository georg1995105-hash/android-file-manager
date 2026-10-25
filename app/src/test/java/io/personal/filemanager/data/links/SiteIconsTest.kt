package io.personal.filemanager.data.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteIconsTest {
    @Test
    fun hostOnlyForHttpLinks() {
        assertEquals("example.com", SiteIcons.hostOf("https://Example.com/path?q=1"))
        assertNull(SiteIcons.hostOf("ftp://example.com"))
        assertNull(SiteIcons.hostOf("javascript:alert(1)"))
        assertNull(SiteIcons.hostOf("не ссылка"))
    }

    @Test
    fun candidatesPreferLargeIconsAndSkipUnsupported() {
        val html = """
            <head>
            <link rel="icon" href="/favicon-16.png" sizes="16x16">
            <link rel='shortcut icon' href='https://cdn.example.com/f.ico'>
            <link href="/apple.png" rel="apple-touch-icon" sizes="180x180">
            <link rel="icon" type="image/svg+xml" href="/logo.svg">
            <link rel="icon" href="data:image/png;base64,AAAA">
            <link rel="icon" href="http://insecure.example.com/i.png">
            <link rel="stylesheet" href="/s.css">
            <link rel="icon" href="icons/32.png?v=2&amp;x=1" sizes="32x32">
            </head>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://example.com/apple.png",
                "https://example.com/a/icons/32.png?v=2&x=1",
                "https://example.com/favicon-16.png",
                "https://cdn.example.com/f.ico",
            ),
            SiteIcons.iconCandidates(html, "https://example.com/a/page"),
        )
    }
}
