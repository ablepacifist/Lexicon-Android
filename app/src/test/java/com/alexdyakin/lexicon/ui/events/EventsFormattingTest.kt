package com.alexdyakin.lexicon.ui.events

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class EventsFormattingTest {
    private lateinit var locale: Locale
    private lateinit var zone: TimeZone

    @Before
    fun pin() {
        locale = Locale.getDefault()
        zone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restore() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    @Test
    fun countsAreSingularOrPlural() {
        assertEquals("1 poll", plural(1, "poll", "polls"))
        assertEquals("0 polls", plural(0, "poll", "polls"))
        assertEquals("3 votes", plural(3, "vote", "votes"))
    }

    @Test
    fun aDateOnlyStaysOnItsDayInAnyZone() {
        // West of UTC is where "2026-11-20" parsed as UTC midnight became Nov 19.
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
        assertEquals("Nov 20, 2026", formatEventDate("2026-11-20"))
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        assertEquals("Nov 20, 2026", formatEventDate("2026-11-20"))
    }

    @Test
    fun anythingElseIsShownAsSent() {
        assertEquals("Saturday evening", formatEventDate("Saturday evening"))
        assertEquals("", formatEventDate(""))
    }
}
