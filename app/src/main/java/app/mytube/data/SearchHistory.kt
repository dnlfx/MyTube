package app.mytube.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Recent searches, newest first. Stored on the phone only. */
object SearchHistory {
    private const val MAX = 15
    private const val KEY = "recent"
    private lateinit var prefs: SharedPreferences
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
        _recent.value = runCatching {
            val array = JSONArray(prefs.getString(KEY, "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        set(listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) })
    }

    fun remove(query: String) = set(_recent.value - query)

    fun clear() = set(emptyList())

    private fun set(list: List<String>) {
        val trimmed = list.take(MAX)
        _recent.value = trimmed
        prefs.edit().putString(KEY, JSONArray(trimmed).toString()).apply()
    }
}
