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
import kotlin.math.max

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

    /** Finished lines waiting for translation and the history file, in order. */
    private val finished = Channel<Line>(Channel.UNLIMITED)
    private var translator: Translator? = null
    private var translatorReady: Deferred<Unit>? = null

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
        scope.launch { for (line in finished) finish(line) }
        worker = Thread({ listen(projection, modelName) }, "captions").also { it.start() }
        return START_NOT_STICKY
    }

    /** Recognition loop. Owns the model, recognizer and recorder, and frees them when it ends. */
    private fun listen(projection: MediaProjection, modelName: String) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            Captions.status.value = "Loading speech model…"
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(Models.path(this, modelName).absolutePath)
            if (!active) return
            record = openRecord(projection) ?: throw IllegalStateException("couldn't capture audio")
            recognizer = Recognizer(model, record.sampleRate.toFloat())
            Captions.status.value = null

            val buffer = ShortArray(record.sampleRate / 10) // 100 ms chunks, so words show up as they're said
            record.startRecording()
            var wasPaused = false
            while (active) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw IllegalStateException("audio capture stopped ($n)")
                if (Captions.paused.value) {
                    // Close off whatever was being said so it isn't glued to what comes after the pause.
                    if (!wasPaused) commit(JSONObject(recognizer.finalResult).optString("text"))
                    wasPaused = true
                    continue
                }
                wasPaused = false
                if (n == 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    commit(JSONObject(recognizer.result).optString("text"))
                } else {
                    Captions.partial.value = JSONObject(recognizer.partialResult).optString("partial")
                }
            }
            commit(JSONObject(recognizer.finalResult).optString("text"))
        } catch (e: Exception) {
            if (active) {
                Captions.error.value = "Captions stopped: ${e.message}"
                main.post { stopSelf() }
            }
        } finally {
            record?.run { runCatching { stop() }; release() }
            recognizer?.close()
            model?.close()
        }
    }

    private fun commit(text: String) {
        Captions.partial.value = ""
        if (text.isBlank()) return
        val line = Line(System.currentTimeMillis(), text.trim().replaceFirstChar { it.uppercase() })
        Captions.add(line)
        finished.trySend(line)
    }

    /** Translates a finished line (when translation is on) and saves it to the history. */
    private suspend fun finish(line: Line) {
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
        val language = Prefs.language(this) ?: ""
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
