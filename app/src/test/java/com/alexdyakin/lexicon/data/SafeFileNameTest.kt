package com.alexdyakin.lexicon.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeFileNameTest {

    @Test
    fun anOrdinaryNameIsKept() {
        assertEquals("Road Trip (2024).mp4", safeFileName("Road Trip (2024).mp4", "fb"))
    }

    @Test
    fun aServerNameCannotClimbOutOfTheFolder() {
        assertEquals("passwd", safeFileName("../../etc/passwd", "fb"))
        assertEquals("evil.exe", safeFileName("..\\..\\evil.exe", "fb"))
        assertEquals("fb", safeFileName("..", "fb"))
    }

    @Test
    fun charactersFileSystemsRejectAreReplaced() {
        assertEquals("a_b_c_.txt", safeFileName("a:b*c?.txt", "fb"))
        assertEquals("tab_name", safeFileName("tab\tname", "fb"))
    }

    @Test
    fun emptyOrMissingFallsBack() {
        assertEquals("media_7", safeFileName(null, "media_7"))
        assertEquals("media_7", safeFileName("   ", "media_7"))
    }

    @Test
    fun aLongNameKeepsItsExtension() {
        val name = safeFileName("x".repeat(300) + ".mkv", "fb")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".mkv"))
    }
}
