package app.mytube.data

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
