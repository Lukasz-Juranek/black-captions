package com.lj.blackcaptions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * One finished caption. [time] doubles as its id. [pending] is the quick Vosk text while the
 * accurate model is still re-reading the phrase.
 */
data class Line(val time: Long, val text: String, val translation: String? = null, val pending: Boolean = false)

/** State shared between the service, the overlay and the screen. */
object Captions {
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)

    /** The sentence still being spoken. Replaced by a [Line] once it ends. */
    val partial = MutableStateFlow("")
    val lines = MutableStateFlow<List<Line>>(emptyList())

    /** Short label for the overlay bar, e.g. "Polish → English". */
    val title = MutableStateFlow("")

    /** Something in progress, e.g. "Loading speech model…". Null when captioning normally. */
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    fun add(line: Line) = lines.update { (it + line).takeLast(200) }

    fun replace(line: Line) = lines.update { all -> all.map { if (it.time == line.time) line else it } }

    fun clear() {
        partial.value = ""
        lines.value = emptyList()
        status.value = null
        paused.value = false
    }
}
