package app.mytube.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatPosition(ms: Long): String = formatDuration(ms / 1000).ifEmpty { "0:00" }

fun formatCount(n: Long): String = when {
    n < 0 -> ""
    n < 1_000 -> n.toString()
    n < 1_000_000 -> compact(n / 1_000.0, "K")
    n < 1_000_000_000 -> compact(n / 1_000_000.0, "M")
    else -> compact(n / 1_000_000_000.0, "B")
}

private fun compact(value: Double, suffix: String): String {
    val text = if (value < 10) String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    else value.toLong().toString()
    return text + suffix
}

fun videoMeta(views: Long, uploaded: String?, live: Boolean = false): String =
    listOfNotNull(
        when {
            live -> "Live"
            views >= 0 -> "${formatCount(views)} views"
            else -> null
        },
        uploaded?.takeIf { it.isNotBlank() },
    ).joinToString("  ·  ")

/** Pulls a YouTube URL out of shared text such as "Check this out https://youtu.be/abc". */
fun extractYouTubeUrl(text: String): String? =
    Regex("""https?://(www\.|m\.)?(youtube\.com|youtu\.be)/\S+""").find(text)?.value

/**
 * "3 days ago" style text for an epoch-millisecond time.
 *
 * Months and years are counted on the calendar, the same way YouTube's own "2 months ago" is
 * turned into a date when lists are read, so that text comes back out unchanged.
 */
fun formatAgo(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0) return ""
    val minutes = ((now - epochMs) / 60_000).coerceAtLeast(0)
    val days = minutes / (60 * 24)
    fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit ago" else "$n ${unit}s ago"
    if (minutes < 1) return "Just now"
    if (minutes < 60) return plural(minutes, "minute")
    if (minutes < 60 * 24) return plural(minutes / 60, "hour")
    if (days < 7) return plural(days, "day")
    val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)
    val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    val months = ChronoUnit.MONTHS.between(then, today)
    return when {
        months < 1 -> plural((days / 7).coerceIn(1, 4), "week")
        months < 12 -> plural(months, "month")
        else -> plural(ChronoUnit.YEARS.between(then, today), "year")
    }
}

/** A timestamp like "2024-05-01T07:00:00-07:00" or "2024-05-01", as YouTube hands out on a video's own page. */
private val timestampText = Regex("""^\s*\d{4}-\d{2}-\d{2}""")

private val shortDate = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** Reads a timestamp like those in [timestampText], or null if [text] isn't one. */
private fun parseTimestamp(text: String): Instant? {
    val t = text.trim()
    return runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(t) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { LocalDate.parse(t.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
}

/**
 * When something went up, in the "3 days ago" form people expect from YouTube.
 *
 * [text] is the date YouTube gave. Lists already say "3 days ago", but a video's own page gives a
 * full timestamp such as "2024-05-01T07:00:00-07:00". [epochMs] is the same moment as a number,
 * or 0 if unknown; it's preferred when known, so a video saved in history or Watch later doesn't
 * keep saying "2 days ago" weeks later. A date still to come (a premiere) is shown as "May 1, 2024".
 */
fun uploadedLabel(text: String?, epochMs: Long = 0, now: Long = System.currentTimeMillis()): String? {
    val streamed = text?.trimStart()?.startsWith("Streamed", ignoreCase = true) == true
    val parsed = text?.takeIf { timestampText.containsMatchIn(it) }?.let(::parseTimestamp)
    val at = epochMs.takeIf { it > 0 } ?: parsed?.toEpochMilli()
    if (at != null) {
        // A minute of slack for clocks that disagree a little.
        if (at > now + 60_000) {
            return shortDate.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }
        val ago = formatAgo(at, now)
        return if (streamed) "Streamed " + ago.replaceFirstChar { it.lowercaseChar() } else ago
    }
    // A timestamp that couldn't be read is still better hidden than shown raw.
    return text?.takeIf { it.isNotBlank() && !timestampText.containsMatchIn(it) }
}
