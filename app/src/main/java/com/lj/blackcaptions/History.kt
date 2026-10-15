package com.lj.blackcaptions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Every finished caption, kept in a private file: time, text and translation, tab-separated. */
object History {
    /** Bumped after every change, so the screen knows to reload. */
    val changed = MutableStateFlow(0)

    private fun file(c: Context) = File(c.filesDir, "history.tsv")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    fun append(c: Context, line: Line) {
        file(c).appendText("${line.time}\t${clean(line.text)}\t${clean(line.translation.orEmpty())}\n")
        changed.value++
    }

    @Synchronized
    fun load(c: Context): List<Line> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { row ->
            val parts = row.split('\t')
            val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Line(time, parts.getOrElse(1) { "" }, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }
    }

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
        changed.value++
    }
}
