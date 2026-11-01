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

/**
 * Channels the user follows. Kept only on the phone: there is no account, so YouTube never
 * learns who you follow. New uploads are read from each channel's public RSS feed.
 */
object Subscriptions {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())

    /** Most recently followed first. */
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "subscriptions.json")
        scope.launch {
            val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
            _channels.update { current -> if (current.isEmpty()) loaded else current }
        }
    }

    fun isSubscribed(url: String?, list: List<Channel> = _channels.value): Boolean =
        url != null && list.any { sameChannel(it.url, url) }

    fun toggle(channel: Channel) {
        val following = isSubscribed(channel.url)
        _channels.update { list ->
            if (following) list.filterNot { sameChannel(it.url, channel.url) } else listOf(channel) + list
        }
        if (following) Feed.drop(channel.url)
        save()
    }

    /** Fills in a better name or avatar for a channel we follow, e.g. once its page has loaded. */
    fun refresh(channel: Channel) {
        val list = _channels.value
        val i = list.indexOfFirst { sameChannel(it.url, channel.url) }
        if (i < 0 || list[i] == channel) return
        _channels.value = list.toMutableList().also { it[i] = channel }
        save()
    }

    private fun sameChannel(a: String, b: String) = channelKey(a) == channelKey(b)

    /** Ignores http/https, www/m and trailing slashes so one channel always matches itself. */
    fun channelKey(url: String): String =
        url.substringAfter("://").removePrefix("www.").removePrefix("m.").trimEnd('/')

    private fun save() {
        val snapshot = _channels.value
        scope.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, "subscriptions.json.tmp")
                tmp.writeText(
                    JSONArray().apply {
                        snapshot.forEach { c ->
                            put(JSONObject().apply {
                                put("url", c.url)
                                put("name", c.name)
                                putOpt("avatar", c.avatar)
                                put("subscribers", c.subscriberCount)
                            })
                        }
                    }.toString(),
                )
                tmp.renameTo(file)
            }
        }
    }

    private fun parse(text: String): List<Channel> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                Channel(
                    url = o.getString("url"),
                    name = o.optString("name"),
                    avatar = if (o.isNull("avatar")) null else o.optString("avatar").ifEmpty { null },
                    subscriberCount = o.optLong("subscribers", -1),
                    verified = false,
                )
            }.getOrNull()
        }
    }
}
