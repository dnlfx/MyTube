package app.mytube.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class HistoryEntry(
    val video: Video,
    val watchedAt: Long,
    val positionMs: Long,
    /** "Up next" videos YouTube showed for this one; the raw material for suggestions. */
    val related: List<Video>,
    /**
     * The furthest you got, from 0 to 1, or -1 if unknown (watched before this was tracked).
     * How much you watched says more than the click itself.
     */
    val watchedFraction: Float = 0f,
) {
    /**
     * How satisfied you seemed with this video, from 0 (opened and left) to 1 (watched it all).
     * Bailing out early counts as a mild "not for me".
     */
    val satisfaction: Double
        get() {
            val f = watchedFraction.toDouble()
            val seconds = video.durationSeconds
            return when {
                f < 0 -> 0.6
                video.isLive -> 0.6
                seconds <= 0 -> 0.5
                f < 0.1 && f * seconds < 60 -> 0.15
                else -> 0.35 + 0.65 * f.coerceAtMost(1.0)
            }
        }
}

/** Watch history, stored as a single JSON file in the app's private storage. Never leaves the phone. */
object History {
    private const val MAX_ENTRIES = 500
    private const val MAX_RELATED = 20

    private lateinit var file: File
    private lateinit var positionsFile: File
    private val positions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Saved resume points by video URL, for progress bars and "Continue watching". */
    val positionsFlow: StateFlow<Map<String, Long>> = _positions.asStateFlow()

    private fun publishPositions() { _positions.value = HashMap(positions) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        positionsFile = File(context.filesDir, "positions.json")
        scope.launch {
            runCatching {
                val o = JSONObject(positionsFile.readText())
                o.keys().forEach { key -> positions.putIfAbsent(key, o.optLong(key)) }
            }
            publishPositions()
            val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
            _entries.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun get(url: String): HistoryEntry? = _entries.value.firstOrNull { it.video.url == url }

    /** Where to resume a video, in milliseconds (0 if never watched). */
    fun position(url: String): Long = positions[url] ?: get(url)?.positionMs ?: 0L

    fun record(video: Video, related: List<Video>) {
        _entries.update { list ->
            val old = list.firstOrNull { it.video.url == video.url }
            val entry = HistoryEntry(
                video = video,
                watchedAt = System.currentTimeMillis(),
                positionMs = 0,
                related = related.take(MAX_RELATED).ifEmpty { old?.related.orEmpty() },
                watchedFraction = old?.watchedFraction?.coerceAtLeast(0f) ?: 0f,
            )
            (listOf(entry) + list.filter { it.video.url != video.url }).take(MAX_ENTRIES)
        }
        save()
    }

    /** Notes how far into [url] you got, if further than before. */
    fun recordProgress(url: String, fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val entry = get(url) ?: return
        // Small steps aren't worth rewriting the file for.
        val before = entry.watchedFraction.coerceAtLeast(0f)
        if (entry.watchedFraction >= 0 && f < before + 0.05f && !(f >= 1f && before < 1f)) return
        _entries.update { list -> list.map { if (it.video.url == url) it.copy(watchedFraction = f) else it } }
        save()
    }

    fun savePosition(url: String, positionMs: Long) {
        if (positions[url] == positionMs) return
        positions[url] = positionMs
        publishPositions()
        scope.launch {
            writeLock.withLock {
                // Keep only videos still in history.
                val known = _entries.value.mapTo(HashSet()) { it.video.url }
                positions.keys.retainAll(known + url)
                val tmp = File(positionsFile.parentFile, "positions.json.tmp")
                tmp.writeText(JSONObject(positions as Map<*, *>).toString())
                tmp.renameTo(positionsFile)
            }
        }
    }

    fun remove(url: String) {
        positions.remove(url)
        publishPositions()
        _entries.update { list -> list.filter { it.video.url != url } }
        save()
    }

    /** Forgets where you stopped in [url], which drops it from "Continue watching" but keeps it in history. */
    fun clearPosition(url: String) = savePosition(url, 0)

    /** Clears every resume point: empties "Continue watching" without touching history. */
    fun clearPositions() {
        positions.clear()
        publishPositions()
        scope.launch { writeLock.withLock { positionsFile.delete() } }
    }

    fun clear() {
        positions.clear()
        publishPositions()
        scope.launch { writeLock.withLock { positionsFile.delete() } }
        _entries.value = emptyList()
        save()
    }

    private fun save() {
        val snapshot = _entries.value
        scope.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, "history.json.tmp")
                tmp.writeText(serialize(snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private fun serialize(list: List<HistoryEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("video", e.video.toJson())
                put("watchedAt", e.watchedAt)
                put("positionMs", e.positionMs)
                put("related", JSONArray().apply { e.related.forEach { put(it.toJson()) } })
                put("watched", e.watchedFraction.toDouble())
            })
        }
    }.toString()

    private fun parse(text: String): List<HistoryEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val related = o.optJSONArray("related") ?: JSONArray()
                HistoryEntry(
                    video = o.getJSONObject("video").toVideo(),
                    watchedAt = o.optLong("watchedAt"),
                    positionMs = o.optLong("positionMs"),
                    related = (0 until related.length()).map { related.getJSONObject(it).toVideo() },
                    watchedFraction = o.optDouble("watched", -1.0).toFloat(),
                )
            }.getOrNull()
        }
    }

}
