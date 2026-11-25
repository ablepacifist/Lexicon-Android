package com.alexdyakin.lexicon.ui.alchemy

import androidx.compose.ui.graphics.Color
import com.alexdyakin.lexicon.data.HoldfastCalendar

// Display only. Every Holdfast figure, level and tone is worked out by alchemyServer
// (HoldfastRules / HoldfastStatusView) and arrives in the status; the app just shows it.

internal val HoldfastOk = Color(0xFF6B8F5E)
internal val HoldfastWarn = Color(0xFFC8A24C)
internal val HoldfastDanger = Color(0xFFB4552F)

/** Severity of a displayed value, driving the chip colour. */
internal enum class HoldfastLevel { OK, WARN, DANGER }

/** The server's "ok" / "warn" / "danger" as a colour level. Anything unknown shows plainly. */
internal fun levelOf(level: String?): HoldfastLevel = when (level) {
    "danger" -> HoldfastLevel.DANGER
    "warn" -> HoldfastLevel.WARN
    else -> HoldfastLevel.OK
}

/** The server's event tone ("danger", "warn", "success", "normal") as a colour, or null for plain text. */
internal fun toneColor(tone: String?): Color? = when (tone) {
    "danger" -> HoldfastDanger
    "warn" -> HoldfastWarn
    "success" -> HoldfastOk
    else -> null
}

/** "43 (6w 1d)", from the calendar the server sends. */
internal fun formatElapsed(calendar: HoldfastCalendar): String {
    if (calendar.weeksElapsed == 0) return calendar.day.toString()
    return if (calendar.dayOfWeek == 0) "${calendar.day} (${calendar.weeksElapsed}w)"
    else "${calendar.day} (${calendar.weeksElapsed}w ${calendar.dayOfWeek}d)"
}
