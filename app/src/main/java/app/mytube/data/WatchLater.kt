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

/** Videos saved for later, newest first. Stored on the phone only. */
object WatchLater {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "watch_later.json")
        scope.launch {
            val loaded = runCatching {
                val array = JSONArray(file.readText())
                (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toVideo() }.getOrNull() }
            }.getOrDefault(emptyList())
            _videos.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun contains(url: String, list: List<Video> = _videos.value) = list.any { it.url == url }

    fun toggle(video: Video) {
        _videos.update { list ->
            if (list.any { it.url == video.url }) list.filterNot { it.url == video.url } else listOf(video) + list
        }
        save()
    }

    fun remove(url: String) {
        _videos.update { list -> list.filterNot { it.url == url } }
        save()
    }

    fun clear() {
        _videos.value = emptyList()
        save()
    }

    private fun save() {
        val snapshot = _videos.value
        scope.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, "watch_later.json.tmp")
                tmp.writeText(JSONArray().apply { snapshot.forEach { put(it.toJson()) } }.toString())
                tmp.renameTo(file)
            }
        }
    }
}
