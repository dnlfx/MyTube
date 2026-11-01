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

/** A channel seen next to a Short, kept as a lead for finding more Shorts you'd like. */
data class ChannelRef(val url: String, val name: String, val avatar: String?)

/** One Short you watched and how it went. */
data class ShortView(
    val video: Video,
    val watchedMs: Long,
    val durationMs: Long,
    val liked: Boolean,
    val at: Long,
    /** Channels YouTube suggested next to it: where to look for similar Shorts. */
    val related: List<ChannelRef>,
) {
    /**
     * How much you liked it, from about -1 (skipped right away) to 3 (liked, or replayed a few times).
     * Watching to the end counts 1, and every replay adds to that.
     */
    val engagement: Double
        get() {
            if (liked) return 3.0
            if (durationMs <= 0) return if (watchedMs > 8_000) 0.5 else 0.0
            val ratio = watchedMs.toDouble() / durationMs
            return when {
                watchedMs < 2_500 && ratio < 0.25 -> -1.0
                ratio < 0.5 -> -0.3
                else -> ratio.coerceAtMost(3.0)
            }
        }
}

/** Shorts you've watched and liked. Stored on the phone only, and only used to pick more Shorts. */
object ShortsHistory {
    private const val MAX = 400
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _views = MutableStateFlow<List<ShortView>>(emptyList())

    /** Newest first. */
    val views: StateFlow<List<ShortView>> = _views.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "shorts_history.json")
        scope.launch {
            val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
            _views.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun isLiked(url: String, list: List<ShortView> = _views.value) = list.any { it.video.url == url && it.liked }

    /** Adds watch time to a Short (a rewatch adds up), keeping a like you already gave it. */
    fun record(video: Video, watchedMs: Long, durationMs: Long, related: List<ChannelRef>) {
        if (watchedMs <= 0) return
        _views.update { list ->
            val old = list.firstOrNull { it.video.url == video.url }
            val view = ShortView(
                video = video,
                watchedMs = (old?.watchedMs ?: 0) + watchedMs,
                durationMs = durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0,
                liked = old?.liked ?: false,
                at = System.currentTimeMillis(),
                related = related.ifEmpty { old?.related.orEmpty() },
            )
            (listOf(view) + list.filter { it.video.url != video.url }).take(MAX)
        }
        save()
    }

    fun toggleLike(video: Video, related: List<ChannelRef>) {
        _views.update { list ->
            val old = list.firstOrNull { it.video.url == video.url }
            val view = old?.copy(liked = !old.liked, related = related.ifEmpty { old.related })
                ?: ShortView(video, 0, 0, liked = true, at = System.currentTimeMillis(), related = related)
            (listOf(view) + list.filter { it.video.url != video.url }).take(MAX)
        }
        save()
    }

    fun clear() {
        _views.value = emptyList()
        save()
    }

    private fun save() {
        val snapshot = _views.value
        scope.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, "shorts_history.json.tmp")
                tmp.writeText(serialize(snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private fun serialize(list: List<ShortView>) = JSONArray().apply {
        list.forEach { v ->
            put(JSONObject().apply {
                put("video", v.video.toJson())
                put("watched", v.watchedMs)
                put("duration", v.durationMs)
                put("liked", v.liked)
                put("at", v.at)
                put("related", JSONArray().apply {
                    v.related.forEach { c ->
                        put(JSONObject().apply { put("url", c.url); put("name", c.name); putOpt("avatar", c.avatar) })
                    }
                })
            })
        }
    }.toString()

    private fun parse(text: String): List<ShortView> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val rel = o.optJSONArray("related") ?: JSONArray()
                ShortView(
                    video = o.getJSONObject("video").toVideo(),
                    watchedMs = o.optLong("watched"),
                    durationMs = o.optLong("duration"),
                    liked = o.optBoolean("liked"),
                    at = o.optLong("at"),
                    related = (0 until rel.length()).map {
                        val c = rel.getJSONObject(it)
                        ChannelRef(c.getString("url"), c.optString("name"), if (c.isNull("avatar")) null else c.optString("avatar"))
                    },
                )
            }.getOrNull()
        }
    }
}
