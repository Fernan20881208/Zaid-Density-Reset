package com.zaid.densityreset.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class ScreenRecordingStartResult(
    val outputUri: Uri,
    val internalAudioRequested: Boolean,
    val internalAudioCaptureStarted: Boolean
)

data class ScreenCaptureGrant(
    val resultCode: Int,
    val data: Intent,
    val requestInternalAudio: Boolean
)

class GameScreenRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var activeSession: RecorderSession? = null

    val isRecording: Boolean
        get() = activeSession?.isRunning == true

    suspend fun start(
        game: SupportedGame,
        resultCode: Int,
        projectionData: Intent,
        requestInternalAudio: Boolean
    ): Result<ScreenRecordingStartResult> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (activeSession?.isRunning == true) {
                return@withContext Result.failure(
                    IllegalStateException("Ya existe una grabación activa.")
                )
            }
        }

        if (resultCode != Activity.RESULT_OK) {
            return@withContext Result.failure(
                IllegalStateException("Android no concedió permiso para grabar la pantalla.")
            )
        }

        val projectionManager = appContext.getSystemService(MediaProjectionManager::class.java)
            ?: return@withContext Result.failure(
                IllegalStateException("MediaProjection no está disponible.")
            )
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, projectionData)
        }.getOrNull() ?: return@withContext Result.failure(
            IllegalStateException("El permiso de captura ya no es válido.")
        )

        val output = runCatching { RecordingOutput.create(appContext, game) }
            .getOrElse { error ->
                projection.stop()
                return@withContext Result.failure(error)
            }
        val dimensions = captureDimensions(appContext)
        val session = runCatching {
            RecorderSession(
                context = appContext,
                game = game,
                projection = projection,
                output = output,
                width = dimensions.first,
                height = dimensions.second,
                densityDpi = appContext.resources.displayMetrics.densityDpi,
                requestInternalAudio = requestInternalAudio &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        appContext,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
            )
        }.getOrElse { error ->
            runCatching { projection.stop() }
            output.discard()
            return@withContext Result.failure(error)
        }
        synchronized(lock) { activeSession = session }
        runCatching { session.start() }.getOrElse { error ->
            synchronized(lock) {
                if (activeSession === session) activeSession = null
            }
            runCatching { session.stop(fromProjectionCallback = false) }
            output.discard()
            return@withContext Result.failure(error)
        }
        Result.success(
            ScreenRecordingStartResult(
                outputUri = output.uri,
                internalAudioRequested = requestInternalAudio,
                internalAudioCaptureStarted = session.internalAudioCaptureStarted
            )
        )
    }

    suspend fun stop(): Result<Uri?> = withContext(Dispatchers.IO) {
        val session = synchronized(lock) {
            activeSession.also { activeSession = null }
        } ?: return@withContext Result.success(null)
        runCatching { session.stop(fromProjectionCallback = false) }
    }

    private inner class RecorderSession(
        private val context: Context,
        private val game: SupportedGame,
        private val projection: MediaProjection,
        private val output: RecordingOutput,
        private val width: Int,
        private val height: Int,
        private val densityDpi: Int,
        private val requestInternalAudio: Boolean
    ) {
        private val stopRequested = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)
        private val videoCodec = createVideoEncoder(width, height)
        private val inputSurface = videoCodec.createInputSurface()
        private var audioCodec: MediaCodec? = null
        private var audioRecord: AudioRecord? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var audioThread: Thread? = null
        private var drainThread: Thread? = null
        @Volatile
        private var recordingHasVideo = false
        private val muxer = MediaMuxer(
            output.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        @Volatile
        var internalAudioCaptureStarted: Boolean = false
            private set

        val isRunning: Boolean
            get() = !stopRequested.get() && !closed.get()

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopRequested.get()) {
                    Thread(
                        { runCatching { stop(fromProjectionCallback = true) } },
                        "density-recorder-projection-stop"
                    ).start()
                }
            }
        }

        fun start() {
            projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            videoCodec.start()

            if (requestInternalAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { configureInternalAudio() }
                    .onFailure {
                        runCatching { audioRecord?.release() }
                        runCatching { audioCodec?.release() }
                        audioRecord = null
                        audioCodec = null
                        internalAudioCaptureStarted = false
                    }
            }

            virtualDisplay = projection.createVirtualDisplay(
                "DensityReset-${game.name}",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )

            audioRecord?.let { record ->
                internalAudioCaptureStarted = startInternalAudio(record)
                if (internalAudioCaptureStarted) {
                    startAudioInputThread(record)
                } else {
                    disableInternalAudio()
                }
            }
            drainThread = Thread(::drainEncoders, "density-recorder-mux").apply { start() }
        }

        fun stop(fromProjectionCallback: Boolean): Uri? {
            if (!stopRequested.compareAndSet(false, true)) {
                drainThread?.join(STOP_JOIN_MILLIS)
                return output.uri.takeIf { output.finalized }
            }

            runCatching { audioRecord?.stop() }
            runCatching { videoCodec.signalEndOfInputStream() }
            audioThread?.join(STOP_JOIN_MILLIS)
            drainThread?.join(STOP_JOIN_MILLIS)
            closeResources(fromProjectionCallback)
            if (recordingHasVideo) output.finalizeOutput() else output.discard()
            return output.uri.takeIf { output.finalized }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        @SuppressLint("MissingPermission")
        private fun configureInternalAudio() {
            check(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) { "El permiso de audio fue revocado antes de iniciar la captura interna." }
            val uid = context.packageManager.getApplicationInfo(game.packageName, 0).uid
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUid(uid)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBuffer > 0) { "Android no ofreció un búfer válido para audio interno." }
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, AUDIO_BUFFER_BYTES))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            require(record.state == AudioRecord.STATE_INITIALIZED) {
                "Android no pudo iniciar la captura de audio interno."
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE,
                    AUDIO_CHANNEL_COUNT
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AUDIO_BUFFER_BYTES)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            codec.start()
            audioRecord = record
            audioCodec = codec
        }

        @SuppressLint("MissingPermission")
        private fun startInternalAudio(record: AudioRecord): Boolean {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return runCatching {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }.getOrDefault(false)
        }

        private fun startAudioInputThread(record: AudioRecord) {
            val codec = audioCodec ?: return
            audioThread = Thread({
                val buffer = ByteArray(AUDIO_BUFFER_BYTES)
                val startedNanos = System.nanoTime()
                try {
                    while (!stopRequested.get()) {
                        val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (read <= 0) continue
                        var queued = false
                        while (!queued && !stopRequested.get()) {
                            val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val input = codec.getInputBuffer(inputIndex)
                                val queuedBytes = input?.let {
                                    it.clear()
                                    minOf(read, it.remaining()).also { size ->
                                        it.put(buffer, 0, size)
                                    }
                                } ?: 0
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    queuedBytes,
                                    (System.nanoTime() - startedNanos) / 1_000L,
                                    0
                                )
                                queued = true
                            }
                        }
                    }
                } finally {
                    queueAudioEndOfStream(codec, startedNanos)
                }
            }, "density-recorder-audio").apply { start() }
        }

        private fun disableInternalAudio() {
            runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            audioRecord = null
            audioCodec = null
            internalAudioCaptureStarted = false
        }

        private fun queueAudioEndOfStream(codec: MediaCodec, startedNanos: Long) {
            repeat(EOS_QUEUE_ATTEMPTS) {
                val inputIndex = runCatching {
                    codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                }.getOrDefault(-1)
                if (inputIndex >= 0) {
                    runCatching {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            (System.nanoTime() - startedNanos) / 1_000L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    }
                    return
                }
            }
        }

        private fun drainEncoders() {
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false
            var videoEos = false
            var audioEos = audioCodec == null
            var videoFirstPts = -1L
            var audioFirstPts = -1L
            var videoLastPts = -1L
            var audioLastPts = -1L
            var wroteVideo = false
            var stopDeadline = Long.MAX_VALUE

            fun maybeStartMuxer() {
                if (!muxerStarted && videoTrack >= 0 && (audioCodec == null || audioTrack >= 0)) {
                    muxer.start()
                    muxerStarted = true
                    runCatching {
                        videoCodec.setParameters(
                            Bundle().apply {
                                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            }
                        )
                    }
                }
            }

            fun writeSample(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
                track: Int,
                isVideo: Boolean
            ) {
                val buffer = codec.getOutputBuffer(index)
                if (
                    buffer != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                    muxerStarted && track >= 0
                ) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val first = if (isVideo) videoFirstPts else audioFirstPts
                    val normalizedFirst = if (first < 0L) info.presentationTimeUs else first
                    if (isVideo && videoFirstPts < 0L) videoFirstPts = normalizedFirst
                    if (!isVideo && audioFirstPts < 0L) audioFirstPts = normalizedFirst
                    val previous = if (isVideo) videoLastPts else audioLastPts
                    val pts = (info.presentationTimeUs - normalizedFirst)
                        .coerceAtLeast(previous + 1L)
                    val adjusted = MediaCodec.BufferInfo().apply {
                        set(0, info.size, pts, info.flags)
                    }
                    muxer.writeSampleData(track, buffer, adjusted)
                    if (isVideo) {
                        videoLastPts = pts
                        wroteVideo = true
                    } else {
                        audioLastPts = pts
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }

            try {
                while (!(videoEos && audioEos)) {
                    if (stopRequested.get() && stopDeadline == Long.MAX_VALUE) {
                        stopDeadline = System.currentTimeMillis() + DRAIN_STOP_TIMEOUT_MILLIS
                    }
                    if (System.currentTimeMillis() >= stopDeadline) break

                    when (val index = videoCodec.dequeueOutputBuffer(videoInfo, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrack < 0) videoTrack = muxer.addTrack(videoCodec.outputFormat)
                            maybeStartMuxer()
                        }
                        else -> if (index >= 0) {
                            writeSample(videoCodec, index, videoInfo, videoTrack, true)
                            if (videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                videoEos = true
                            }
                        }
                    }

                    audioCodec?.let { codec ->
                        when (val index = codec.dequeueOutputBuffer(audioInfo, CODEC_TIMEOUT_US)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (audioTrack < 0) audioTrack = muxer.addTrack(codec.outputFormat)
                                maybeStartMuxer()
                            }
                            else -> if (index >= 0) {
                                writeSample(codec, index, audioInfo, audioTrack, false)
                                if (audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    audioEos = true
                                }
                            }
                        }
                    }
                }
            } finally {
                val stoppedCleanly = muxerStarted && runCatching {
                    muxer.stop()
                }.isSuccess
                recordingHasVideo = stoppedCleanly && wroteVideo
            }
        }

        private fun closeResources(fromProjectionCallback: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            runCatching { virtualDisplay?.release() }
            runCatching { inputSurface.release() }
            runCatching { audioRecord?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { videoCodec.stop() }
            runCatching { videoCodec.release() }
            runCatching { muxer.release() }
            runCatching { projection.unregisterCallback(projectionCallback) }
            if (!fromProjectionCallback) runCatching { projection.stop() }
            synchronized(lock) {
                if (activeSession === this) activeSession = null
            }
        }
    }
}

private class RecordingOutput private constructor(
    private val context: Context,
    val uri: Uri,
    private val descriptor: android.os.ParcelFileDescriptor,
    private val pending: Boolean
) {
    val fileDescriptor: java.io.FileDescriptor
        get() = descriptor.fileDescriptor

    @Volatile
    var finalized: Boolean = false
        private set

    fun finalizeOutput() {
        if (finalized) return
        if (pending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
        runCatching { descriptor.close() }
        finalized = true
    }

    fun discard() {
        runCatching { descriptor.close() }
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        fun create(context: Context, game: SupportedGame): RecordingOutput {
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "DensityReset-${game.name}-$date.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_MOVIES}/Density Reset"
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val uri = requireNotNull(context.contentResolver.insert(collection, values)) {
                "Android no pudo crear el archivo de grabación."
            }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: run {
                    context.contentResolver.delete(uri, null, null)
                    error("Android no pudo abrir el archivo de grabación.")
                }
            return RecordingOutput(
                context = context,
                uri = uri,
                descriptor = descriptor,
                pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            )
        }
    }
}

private fun createVideoEncoder(width: Int, height: Int): MediaCodec {
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val bitRate = (width.toLong() * height.toLong() * VIDEO_BITS_PER_PIXEL)
        .coerceIn(MIN_VIDEO_BIT_RATE.toLong(), MAX_VIDEO_BIT_RATE.toLong())
        .toInt()
    codec.configure(
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL_SECONDS)
        },
        null,
        null,
        MediaCodec.CONFIGURE_FLAG_ENCODE
    )
    return codec
}

private fun captureDimensions(context: Context): Pair<Int, Int> {
    val metrics = context.resources.displayMetrics
    var width = metrics.widthPixels.coerceAtLeast(MIN_CAPTURE_EDGE)
    var height = metrics.heightPixels.coerceAtLeast(MIN_CAPTURE_EDGE)
    val longest = maxOf(width, height)
    if (longest > MAX_CAPTURE_EDGE) {
        val scale = MAX_CAPTURE_EDGE.toFloat() / longest.toFloat()
        width = (width * scale).roundToInt()
        height = (height * scale).roundToInt()
    }
    width = width.coerceAtLeast(MIN_CAPTURE_EDGE) and 1.inv()
    height = height.coerceAtLeast(MIN_CAPTURE_EDGE) and 1.inv()
    return width to height
}

private const val VIDEO_FRAME_RATE = 30
private const val VIDEO_I_FRAME_INTERVAL_SECONDS = 2
private const val VIDEO_BITS_PER_PIXEL = 5L
private const val MIN_VIDEO_BIT_RATE = 4_000_000
private const val MAX_VIDEO_BIT_RATE = 14_000_000
private const val MIN_CAPTURE_EDGE = 320
private const val MAX_CAPTURE_EDGE = 1_920
private const val AUDIO_SAMPLE_RATE = 48_000
private const val AUDIO_CHANNEL_COUNT = 2
private const val AUDIO_BIT_RATE = 128_000
private const val AUDIO_BUFFER_BYTES = 16_384
private const val CODEC_TIMEOUT_US = 10_000L
private const val EOS_QUEUE_ATTEMPTS = 20
private const val DRAIN_STOP_TIMEOUT_MILLIS = 6_000L
private const val STOP_JOIN_MILLIS = 7_000L
