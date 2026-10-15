package com.lj.blackcaptions

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A downloadable Vosk speech model. [lang] is Vosk's code ("pl", "en-us", "cn", …). */
data class VoskModel(val name: String, val lang: String, val language: String, val size: String, val url: String)

/**
 * The small Vosk models, listed from alphacephei.com, downloaded and unpacked into app storage.
 * The list is cached, so the app still knows the models after going offline.
 */
object Models {
    private const val LIST_URL = "https://alphacephei.com/vosk/models/model-list.json"

    val list = MutableStateFlow<List<VoskModel>>(emptyList())
    val listFailed = MutableStateFlow(false)
    val installed = MutableStateFlow<Set<String>>(emptySet())

    /** Model name → download percent, or -1 while unpacking. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(c: Context) = File(c.filesDir, "models")
    fun path(c: Context, name: String) = File(dir(c), name)
    private fun cache(c: Context) = File(c.filesDir, "model-list.json")

    fun refresh(c: Context) {
        val app = c.applicationContext
        scope.launch {
            installed.value = installedNames(app)
            cache(app).takeIf { it.exists() }?.let { list.value = parse(it.readText()) }
            try {
                val json = URL(LIST_URL).readText()
                list.value = parse(json)
                cache(app).writeText(json)
                listFailed.value = false
            } catch (e: Exception) {
                listFailed.value = list.value.isEmpty()
            }
        }
    }

    private fun installedNames(c: Context) =
        dir(c).listFiles()?.filter { it.isDirectory && !it.name.endsWith(".part") }?.map { it.name }?.toSet() ?: emptySet()

    private fun parse(json: String): List<VoskModel> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "small" && it.optString("obsolete") != "true" }
            .map {
                VoskModel(
                    it.getString("name"), it.getString("lang"), it.optString("lang_text", it.getString("lang")),
                    it.optString("size_text"), it.getString("url")
                )
            }
            .sortedBy { it.language }
    }

    fun download(c: Context, m: VoskModel, onDone: () -> Unit) {
        val app = c.applicationContext
        if (m.name in progress.value) return
        progress.update { it + (m.name to 0) }
        scope.launch {
            val zip = File(app.cacheDir, "${m.name}.zip")
            val tmp = File(dir(app), "${m.name}.part")
            try {
                fetch(m.url, zip) { pct -> progress.update { it + (m.name to pct) } }
                progress.update { it + (m.name to -1) }
                tmp.deleteRecursively()
                unzip(zip, tmp)
                // The archives hold one top-level folder named after the model.
                val root = tmp.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: tmp
                val target = path(app, m.name)
                target.deleteRecursively()
                if (!root.renameTo(target)) throw IOException("couldn't move the model into place")
                installed.value = installedNames(app)
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                Captions.error.value = "Download of ${m.language} failed: ${e.message}"
            } finally {
                zip.delete()
                tmp.deleteRecursively()
                progress.update { it - m.name }
            }
        }
    }

    fun delete(c: Context, name: String) {
        val app = c.applicationContext
        scope.launch {
            path(app, name).deleteRecursively()
            installed.value = installedNames(app)
        }
    }

    private fun fetch(url: String, to: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) onProgress(pct).also { last = pct }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, into: File) {
        into.mkdirs()
        val base = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(into, entry.name)
                // Refuse entries that would land outside the target folder.
                if (!out.canonicalPath.startsWith(base)) throw IOException("bad archive entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }
}
