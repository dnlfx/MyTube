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
)

/** Watch history, stored as a single JSON file in the app's private storage. Never leaves the phone. */
object History {
    private const val MAX_ENTRIES = 500
    private const val MAX_RELATED = 20

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        scope.launch {
            val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
            _entries.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun get(url: String): HistoryEntry? = _entries.value.firstOrNull { it.video.url == url }

    fun record(video: Video, related: List<Video>) {
        _entries.update { list ->
            val old = list.firstOrNull { it.video.url == video.url }
            val entry = HistoryEntry(
                video = video,
                watchedAt = System.currentTimeMillis(),
                positionMs = old?.positionMs ?: 0,
                related = related.take(MAX_RELATED).ifEmpty { old?.related.orEmpty() },
            )
            (listOf(entry) + list.filter { it.video.url != video.url }).take(MAX_ENTRIES)
        }
        save()
    }

    fun savePosition(url: String, positionMs: Long) {
        _entries.update { list -> list.map { if (it.video.url == url) it.copy(positionMs = positionMs) else it } }
        save()
    }

    fun remove(url: String) {
        _entries.update { list -> list.filter { it.video.url != url } }
        save()
    }

    fun clear() {
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
                )
            }.getOrNull()
        }
    }

    private fun Video.toJson() = JSONObject().apply {
        put("url", url)
        put("title", title)
        put("channelName", channelName)
        putOpt("channelUrl", channelUrl)
        putOpt("channelAvatar", channelAvatar)
        putOpt("thumbnail", thumbnail)
        put("duration", durationSeconds)
        put("views", viewCount)
        putOpt("uploaded", uploaded)
        put("live", isLive)
    }

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.toVideo() = Video(
        url = getString("url"),
        title = optString("title"),
        channelName = optString("channelName"),
        channelUrl = str("channelUrl"),
        channelAvatar = str("channelAvatar"),
        thumbnail = str("thumbnail"),
        durationSeconds = optLong("duration"),
        viewCount = optLong("views", -1),
        uploaded = str("uploaded"),
        isLive = optBoolean("live"),
    )
}
