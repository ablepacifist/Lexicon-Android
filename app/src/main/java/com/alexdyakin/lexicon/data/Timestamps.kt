package com.alexdyakin.lexicon.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Calendar
import java.util.TimeZone

/**
 * `yyyy-MM-dd'T'HH:mm:ss` with optional fractional seconds and optional zone.
 * Also tolerates a space instead of `T`.
 */
private val ISO_LOCAL = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:?\d{2})?$"""
)

/**
 * Parses a server timestamp to epoch millis, accepting either epoch millis already or an
 * ISO-8601 date-time string. Returns 0 for anything unrecognisable rather than throwing —
 * callers treat 0 as "unknown", which degrades to "start from the beginning".
 *
 * A timestamp with **no zone** is read as LOCAL time, because that is what
 * `new Date("2026-08-16T07:25:51.903915")` does in the browser and the web client is the
 * behaviour we must match.
 *
 * Written by hand rather than with `java.time` because minSdk is 24 and core library
 * desugaring is not enabled; and `SimpleDateFormat` cannot be used directly because its `S`
 * pattern means milliseconds — it would read the 6-digit microseconds `.903915` as 903,915 ms,
 * putting every timestamp ~15 minutes into the future.
 */
fun parseTimestampMillis(raw: String?): Long {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0L
    text.toLongOrNull()?.let { return it }

    val match = ISO_LOCAL.matchEntire(text) ?: return 0L
    val g = match.groupValues

    val fraction = g[7]
    val millis = if (fraction.isEmpty()) 0 else fraction.take(3).padEnd(3, '0').toInt()

    val zone = g[8]
    val timeZone = when {
        zone.isEmpty() -> TimeZone.getDefault()
        zone.equals("Z", ignoreCase = true) -> TimeZone.getTimeZone("UTC")
        else -> TimeZone.getTimeZone("GMT$zone")
    }

    val calendar = Calendar.getInstance(timeZone)
    calendar.clear()
    calendar.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
    calendar.set(Calendar.MILLISECOND, millis)
    return calendar.timeInMillis
}

/**
 * Epoch-millis field that survives the server sending a date STRING instead of a number.
 *
 * Load-bearing: `/api/livestream/state` returns
 * `"currentStartTime":"2026-08-16T07:25:51.903915"`. Declaring that field as a plain `Long`
 * made kotlinx.serialization throw on the type mismatch, which failed the decode of the whole
 * `LiveStreamState` — so the SSE `init` handler returned early and the live stream never
 * played anything, with no error shown anywhere. Pinned by LiveStreamStateSerializationTest.
 */
object FlexibleEpochMillisSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleEpochMillis", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return 0L
        primitive.longOrNull?.let { return it }
        return parseTimestampMillis(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}
