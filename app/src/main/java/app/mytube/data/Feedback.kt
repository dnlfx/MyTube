package app.mytube.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Not interested" and "Don't recommend channel", like YouTube's. Hidden videos and channels
 * never come back in Home, Suggested or Shorts. Stored on the phone only.
 */
object Feedback {
    data class State(
        val hiddenVideos: Set<String> = emptySet(),
        /** Channel keys (see [Subscriptions.channelKey]) mapped to the channel, so they can be listed and undone. */
        val hiddenChannels: Map<String, ChannelRef> = emptyMap(),
    ) {
        fun hides(video: Video): Boolean =
            video.url in hiddenVideos || video.channelUrl?.let { Subscriptions.channelKey(it) in hiddenChannels } == true

        fun hidesChannel(url: String?): Boolean = url != null && Subscriptions.channelKey(url) in hiddenChannels
    }

    private const val MAX_VIDEOS = 1000
    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("feedback", Context.MODE_PRIVATE)
        _state.value = runCatching {
            val videos = JSONArray(prefs.getString("videos", "[]"))
            val channels = JSONArray(prefs.getString("channels", "[]"))
            State(
                hiddenVideos = (0 until videos.length()).mapTo(LinkedHashSet()) { videos.getString(it) },
                hiddenChannels = (0 until channels.length()).associate {
                    val o = channels.getJSONObject(it)
                    val ref = ChannelRef(o.getString("url"), o.optString("name"), if (o.isNull("avatar")) null else o.optString("avatar"))
                    Subscriptions.channelKey(ref.url) to ref
                },
            )
        }.getOrDefault(State())
    }

    fun notInterested(video: Video) = set(_state.value.copy(hiddenVideos = (_state.value.hiddenVideos + video.url)))

    fun hideChannel(url: String, name: String, avatar: String?) =
        set(_state.value.copy(hiddenChannels = _state.value.hiddenChannels + (Subscriptions.channelKey(url) to ChannelRef(url, name, avatar))))

    fun unhideChannel(url: String) =
        set(_state.value.copy(hiddenChannels = _state.value.hiddenChannels - Subscriptions.channelKey(url)))

    fun undoNotInterested(url: String) = set(_state.value.copy(hiddenVideos = _state.value.hiddenVideos - url))

    fun clear() = set(State())

    private fun set(state: State) {
        val trimmed = if (state.hiddenVideos.size > MAX_VIDEOS) state.copy(hiddenVideos = state.hiddenVideos.toList().takeLast(MAX_VIDEOS).toSet()) else state
        _state.value = trimmed
        prefs.edit()
            .putString("videos", JSONArray(trimmed.hiddenVideos.toList()).toString())
            .putString(
                "channels",
                JSONArray().apply {
                    trimmed.hiddenChannels.values.forEach { c ->
                        put(JSONObject().apply { put("url", c.url); put("name", c.name); putOpt("avatar", c.avatar) })
                    }
                }.toString(),
            )
            .apply()
    }
}
