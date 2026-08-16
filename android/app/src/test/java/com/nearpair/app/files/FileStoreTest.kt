package com.nearpair.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileStoreTest {
    @Test
    fun filenameSanitizerRemovesTraversalAndSeparators() {
        val sanitized = FileStore.sanitizeFileName("../../private\\movie:final?.mp4")
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
        assertFalse(sanitized.startsWith("."))
        assertEquals("movie_final_.mp4", sanitized)
    }

    @Test
    fun filenameSanitizerNeverReturnsBlank() {
        assertEquals("received-file", FileStore.sanitizeFileName("..."))
    }
}

