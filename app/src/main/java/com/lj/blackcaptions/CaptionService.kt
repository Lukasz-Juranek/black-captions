package com.lj.blackcaptions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.Translator
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Captures the audio other apps are playing, turns speech into text with Vosk, optionally
 * translates each finished sentence with ML Kit, and shows it all in the floating [Overlay].
 * Everything runs on the phone; only the models are downloaded.
 */
class CaptionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var overlay: Overlay? = null
    private var worker: Thread? = null
    @Volatile private var active = false

    /** Finished phrases waiting for the accurate pass, translation and the history file, in order. */
    private val finished = Channel<Phrase>(Channel.UNLIMITED)

    /** A finished line, plus its audio when the accurate model will re-read it. */
    private class Phrase(val line: Line, val audio: ShortArray?, val rate: Int)

    /** The accurate model for this session, or null when Vosk's text is final. */
    private var accurateModel: AccurateModel? = null
    private var translator: Translator? = null
    private var translatorReady: Deferred<Unit>? = null
    private var lastTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                Captions.paused.value = !Captions.paused.value
                updateNotification()
                return START_NOT_STICKY
            }
            ACTION_RESTYLE -> {
                overlay?.applyStyle()
                return START_NOT_STICKY
            }
            ACTION_TRANSLATE -> {
                if (active) setUpTranslator()
                return START_NOT_STICKY
            }
        }
        if (active) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val modelName = Prefs.model(this)
        if (code != Activity.RESULT_OK || data == null || modelName == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Android 14+ wants the foreground service running before the projection is created.
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (e: Exception) {
            Captions.error.value = "Couldn't start: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        val projection = projection ?: run { stopSelf(); return START_NOT_STICKY }
        // Stopped from the system's "sharing" chip, or by another app taking over.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, main)

        active = true
        accurateModel = Models.accurateFor(Prefs.lang(this))
            ?.takeIf { Prefs.accurate(this) && Models.path(this, it.id).isDirectory }
        Captions.clear()
        Captions.error.value = null
        Captions.running.value = true
        updateTitle()

        overlay = try {
            Overlay(this, onPause = { pause(this) }, onClose = { stopSelf() }).also { it.show() }
        } catch (e: Exception) {
            Captions.error.value = "Allow \"Display over other apps\" for Black Captions"
            stopSelf()
            return START_NOT_STICKY
        }

        setUpTranslator()
        scope.launch {
            val accurate = accurateModel?.let(::loadAccurate)
            try {
                for (phrase in finished) finish(phrase, accurate)
            } finally {
                accurate?.release()
            }
        }
        worker = Thread({ listen(projection, modelName) }, "captions").also { it.start() }
        return START_NOT_STICKY
    }

    /** Recognition loop. Owns the model, recognizer, recorder and voice detector, and frees them when it ends. */
    private fun listen(projection: MediaProjection, modelName: String) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        var vad: Vad? = null
        try {
            Captions.status.value = "Loading speech model…"
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(Models.path(this, modelName).absolutePath)
            if (!active) return
            record = openRecord(projection) ?: throw IllegalStateException("couldn't capture audio")
            recognizer = Recognizer(model, record.sampleRate.toFloat())
            // With the accurate model, a voice detector decides where phrases start and end, and Vosk
            // only shows the words as they're said.
            vad = if (accurateModel != null) loadVad() else null
            Captions.status.value = null
            val buffer = ShortArray(record.sampleRate / 10) // 100 ms chunks, so words show up as they're said
            record.startRecording()
            if (vad != null) splitByVoice(record, recognizer, vad, buffer) else splitByVosk(record, recognizer, buffer)
        } catch (e: Exception) {
            if (active) {
                Captions.error.value = "Captions stopped: ${e.message}"
                main.post { stopSelf() }
            }
        } finally {
            record?.run { runCatching { stop() }; release() }
            recognizer?.close()
            model?.close()
            vad?.release()
        }
    }

    private fun read(record: AudioRecord, buffer: ShortArray): Int {
        val n = record.read(buffer, 0, buffer.size)
        if (n < 0) throw IllegalStateException("audio capture stopped ($n)")
        return n
    }

    private fun textOf(json: String) = JSONObject(json).optString("text")

    /** Vosk alone: its own pauses end the lines and its text is final. */
    private fun splitByVosk(record: AudioRecord, recognizer: Recognizer, buffer: ShortArray) {
        var wasPaused = false
        while (active) {
            val n = read(record, buffer)
            if (Captions.paused.value) {
                // Close off whatever was being said so it isn't glued to what comes after the pause.
                if (!wasPaused) commit(textOf(recognizer.finalResult), null, 0)
                wasPaused = true
                continue
            }
            wasPaused = false
            if (n == 0) continue
            if (recognizer.acceptWaveForm(buffer, n)) commit(textOf(recognizer.result), null, 0)
            else Captions.partial.value = JSONObject(recognizer.partialResult).optString("partial")
        }
        commit(textOf(recognizer.finalResult), null, 0)
    }

    /**
     * Accurate mode. Silero VAD finds each stretch of speech, which goes to the accurate model with a
     * little real audio on both sides. Vosk's words since the last phrase are shown live and become the
     * line's placeholder text. Words Vosk "hears" while nobody is speaking (music, noise) are dropped.
     */
    private fun splitByVoice(record: AudioRecord, recognizer: Recognizer, vad: Vad, buffer: ShortArray) {
        val rate = record.sampleRate
        val recent = Recent(rate * RECENT_SECONDS)
        val down = Downsampler(rate)
        val heard = StringBuilder()
        var partial = ""
        var lastVoice = -rate * 10L // where the detector last heard a voice, in captured samples

        // Turns finished speech segments into lines. Vosk has heard the same audio, so its words so far
        // belong to the last of them.
        fun endPhrases(): Boolean {
            if (vad.empty()) return false
            while (!vad.empty()) {
                val seg = vad.front()
                vad.pop()
                val last = vad.empty()
                if (last) heard.append(' ').append(textOf(recognizer.finalResult))
                val from = seg.start.toLong() * rate / VAD_RATE - rate * PAD_BEFORE_MS / 1000
                val to = (seg.start.toLong() + seg.samples.size) * rate / VAD_RATE + rate * PAD_AFTER_MS / 1000
                commit(if (last) heard.toString() else "", recent.slice(from, to), rate)
            }
            heard.clear()
            partial = ""
            return true
        }

        var wasPaused = false
        while (active) {
            val n = read(record, buffer)
            if (Captions.paused.value) {
                // Close off whatever was being said so it isn't glued to what comes after the pause.
                if (!wasPaused) {
                    vad.flush()
                    endPhrases()
                    recognizer.finalResult
                    heard.clear()
                    partial = ""
                    Captions.partial.value = ""
                }
                wasPaused = true
                continue
            }
            wasPaused = false
            if (n == 0) continue
            recent.add(buffer, n)
            vad.acceptWaveform(down.process(buffer, n))
            if (vad.isSpeechDetected() || !vad.empty()) lastVoice = recent.total
            if (recognizer.acceptWaveForm(buffer, n)) {
                partial = ""
                val text = textOf(recognizer.result)
                if (recent.total - lastVoice < rate * VOICE_GRACE_SECONDS) heard.append(' ').append(text)
            } else {
                partial = JSONObject(recognizer.partialResult).optString("partial")
            }
            if (!endPhrases()) Captions.partial.value = "$heard $partial".trim()
        }
        vad.flush()
        endPhrases()
    }

    private fun loadVad(): Vad? = try {
        Vad(
            assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.45f,
                    // About a sentence break; shorter pauses stay inside the phrase.
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    // Longer speech is split at a quiet moment.
                    maxSpeechDuration = MAX_PHRASE_SECONDS,
                ),
                sampleRate = VAD_RATE,
                numThreads = 1,
            )
        )
    } catch (e: Throwable) {
        Captions.error.value = "Couldn't load the voice detector; showing quick captions instead"
        null
    }

    private fun commit(text: String, audio: ShortArray?, rate: Int) {
        Captions.partial.value = ""
        val clean = text.trim().replace(SPACES, " ")
        // With audio the accurate model may still find words Vosk missed, so the phrase is kept.
        if (clean.isEmpty() && audio == null) return
        // Ids must be unique, and two lines can end in the same millisecond.
        lastTime = max(System.currentTimeMillis(), lastTime + 1)
        val line = Line(lastTime, clean.ifEmpty { PLACEHOLDER }.replaceFirstChar { it.uppercase() }, pending = audio != null)
        Captions.add(line)
        finished.trySend(Phrase(line, audio, rate))
    }

    private fun loadAccurate(m: AccurateModel): OfflineRecognizer? {
        val dir = Models.path(this, m.id)
        val status = "Loading ${m.label}…"
        Captions.status.compareAndSet(null, status)
        return try {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = File(dir, m.model).absolutePath),
                        tokens = File(dir, m.tokens).absolutePath,
                        numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
                    )
                )
            )
        } catch (e: Throwable) {
            Captions.error.value = "Couldn't load ${m.label}; showing quick captions instead"
            null
        } finally {
            Captions.status.compareAndSet(status, null)
        }
    }

    /**
     * Re-reads the phrase with the accurate model when there is one. Quiet audio is turned up, and a
     * little silence goes on both ends: CTC models often drop a word that touches the edge of the clip.
     */
    private fun reread(recognizer: OfflineRecognizer, audio: ShortArray, rate: Int): String {
        if (audio.size < rate / 5) return "" // too short to hold a word
        var peak = 1
        for (s in audio) peak = max(peak, abs(s.toInt()))
        val gain = (0.9f / peak).coerceAtMost(MAX_GAIN / 32768f)
        val pad = rate / 4
        val samples = FloatArray(audio.size + 2 * pad)
        for (i in audio.indices) samples[pad + i] = audio[i] * gain
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, rate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /**
     * Swaps in the accurate text, translates the line (when translation is on) and saves it
     * to the history.
     */
    private suspend fun finish(phrase: Phrase, accurate: OfflineRecognizer?) {
        var line = phrase.line
        if (line.pending) {
            val better = if (accurate != null && phrase.audio != null) {
                try {
                    reread(accurate, phrase.audio, phrase.rate)
                } catch (e: Exception) {
                    ""
                }
            } else ""
            // Neither model found words: it wasn't speech after all.
            val text = better.ifBlank { line.text.takeIf { it != PLACEHOLDER } }
                ?: return Captions.remove(line.time)
            line = line.copy(text = text, pending = false)
            Captions.replace(line)
        }
        val t = translator
        val done = if (t == null) line else try {
            translatorReady?.await()
            line.copy(translation = t.translate(line.text).await()).also { Captions.replace(it) }
        } catch (e: Exception) {
            line
        }
        History.append(this, done)
    }

    private fun setUpTranslator() {
        translator?.close()
        translator = null
        translatorReady = null
        updateTitle()
        val target = Prefs.target(this) ?: return
        val source = Prefs.lang(this)?.let(Translation::fromVosk) ?: return
        if (source == target) return
        val t = Translation.client(source, target)
        translator = t
        translatorReady = scope.async {
            Captions.status.value = "Downloading ${Translation.name(target)} translation…"
            try {
                t.downloadModelIfNeeded().await()
            } catch (e: Exception) {
                Captions.error.value = "Translation model download failed: ${e.message}"
                throw e
            } finally {
                Captions.status.value = null
            }
        }
    }

    private fun updateTitle() {
        val language = (Prefs.language(this) ?: "") + (accurateModel?.let { " · ${it.label}" } ?: "")
        val source = Prefs.lang(this)?.let(Translation::fromVosk)
        val target = Prefs.target(this)
        Captions.title.value =
            if (target != null && source != null && source != target) "$language → ${Translation.name(target)}" else language
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service is started.
    private fun openRecord(projection: MediaProjection): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        // 16 kHz is what the models use; some phones only capture at the output rate, and Vosk resamples that.
        for (rate in intArrayOf(16_000, 48_000, 44_100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record = try {
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(min, rate)) // at least half a second of 16-bit audio
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } catch (e: Exception) {
                null
            } ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Captions", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val paused = Captions.paused.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (paused) "Captions paused" else "Captions on")
            .setContentText(Prefs.language(this) ?: "")
            .setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", pause)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (active) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        active = false
        worker?.join(1_000)
        worker = null
        overlay?.remove()
        overlay = null
        finished.close()
        translator?.close()
        scope.cancel()
        projection?.stop()
        projection = null
        Captions.running.value = false
        Captions.clear()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "captions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESTYLE = "restyle"
        private const val ACTION_TRANSLATE = "translate"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        /** Longest phrase given to the accurate model; it was trained on clips up to about 25 s. */
        private const val MAX_PHRASE_SECONDS = 20f
        /** How much captured audio is kept for cutting out phrases. */
        private const val RECENT_SECONDS = 45
        /** The voice detector's sample rate. */
        private const val VAD_RATE = 16_000
        /** Real audio kept around each detected phrase so its first and last sounds aren't clipped. */
        private const val PAD_BEFORE_MS = 300
        private const val PAD_AFTER_MS = 200
        /** Vosk's words count only this soon after the detector last heard a voice. */
        private const val VOICE_GRACE_SECONDS = 2
        /** Shown while the accurate model reads a phrase Vosk found no words in. */
        private const val PLACEHOLDER = "…"
        private val SPACES = Regex("\\s+")
        /** Most that quiet audio is amplified before the accurate pass. */
        private const val MAX_GAIN = 8f

        /** Starts captioning with the screen-capture consent the user just gave. */
        fun start(c: Context, resultCode: Int, data: Intent) = ContextCompat.startForegroundService(
            c, Intent(c, CaptionService::class.java).putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
        )

        fun stop(c: Context) = send(c, ACTION_STOP)
        fun pause(c: Context) = send(c, ACTION_PAUSE)

        /** Re-reads text size and background after they changed. */
        fun restyle(c: Context) = send(c, ACTION_RESTYLE)

        /** Switches to the newly chosen translation language. */
        fun retranslate(c: Context) = send(c, ACTION_TRANSLATE)

        private fun send(c: Context, action: String) {
            if (Captions.running.value) c.startService(Intent(c, CaptionService::class.java).setAction(action))
        }
    }
}

/** The last [size] captured samples, addressed by their position since capture started. */
private class Recent(size: Int) {
    private val data = ShortArray(size)
    var total = 0L
        private set

    fun add(src: ShortArray, n: Int) {
        for (i in 0 until n) data[((total + i) % data.size).toInt()] = src[i]
        total += n
    }

    /** Samples [from] until [to], trimmed to what is still kept. */
    fun slice(from: Long, to: Long): ShortArray {
        val a = max(from, max(0L, total - data.size))
        val b = min(to, total)
        if (b <= a) return ShortArray(0)
        return ShortArray((b - a).toInt()) { data[((a + it) % data.size).toInt()] }
    }
}

/**
 * Brings captured audio to the voice detector's 16 kHz by averaging. Rough, but only the detector
 * hears it; the accurate model gets the original audio. Input sample n lands in output sample
 * n * 16000 / rate, so positions map back exactly.
 */
private class Downsampler(private val rate: Int) {
    private var n = 0L
    private var bucket = 0L
    private var sum = 0f
    private var count = 0

    fun process(input: ShortArray, len: Int): FloatArray {
        if (rate == 16_000) return FloatArray(len) { input[it] / 32768f }
        val out = FloatArray(len * 16_000 / rate + 2)
        var k = 0
        for (i in 0 until len) {
            val b = n * 16_000 / rate
            if (b != bucket) {
                out[k++] = sum / count
                sum = 0f
                count = 0
                bucket = b
            }
            sum += input[i] / 32768f
            count++
            n++
        }
        return out.copyOf(k)
    }
}
