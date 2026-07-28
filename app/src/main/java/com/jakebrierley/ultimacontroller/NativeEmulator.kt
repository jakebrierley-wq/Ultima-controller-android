/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.jakebrierley.ultimacontroller

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

enum class EmulatorState {
    IDLE,
    STARTING,
    RUNNING,
    PAUSED,
    ERROR,
}

data class EmulatorStatus(
    val state: EmulatorState,
    val message: String,
)

class NativeEmulator(
    context: Context,
    private val statusListener: (EmulatorStatus) -> Unit,
) : EmulatorInput, TextureView.SurfaceTextureListener, Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val resumed = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null
    private var attachedTextureView: TextureView? = null
    private var displaySurface: Surface? = null
    private var libraryReady = false
    @Volatile
    private var videoStatusMessage =
        "DOSBox Pure loaded; waiting for its first visible frame"
    @Volatile
    private var videoReady = false
    @Volatile
    private var started = false

    val isRunning: Boolean
        get() = started

    fun attachTo(textureView: TextureView) {
        if (attachedTextureView === textureView) return
        attachedTextureView?.surfaceTextureListener = null
        releaseDisplaySurface()
        attachedTextureView = textureView
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let(::replaceDisplaySurface)
        }
    }

    fun start(gameExecutable: File, archiveSha256: String): Boolean {
        if (started) return true
        if (!ensureNativeLibrary()) {
            publishStatus(
                EmulatorState.ERROR,
                "Native emulator library could not be loaded",
            )
            return false
        }
        if (displaySurface?.isValid != true) {
            attachedTextureView
                ?.takeIf { it.isAvailable }
                ?.surfaceTexture
                ?.let(::replaceDisplaySurface)
        }
        displaySurface?.takeIf(Surface::isValid)?.let(::setSurface)
        if (!gameExecutable.isFile || gameExecutable.length() <= 0L) {
            publishStatus(
                EmulatorState.ERROR,
                "The imported ULTIMA.EXE is missing; import the game ZIP again",
            )
            return false
        }

        val systemDirectory = File(appContext.filesDir, SYSTEM_DIRECTORY_NAME)
        val saveDirectory = File(
            File(appContext.filesDir, SAVE_DIRECTORY_NAME),
            archiveSha256,
        )
        if (!systemDirectory.ensureDirectory() || !saveDirectory.ensureDirectory()) {
            publishStatus(
                EmulatorState.ERROR,
                "Could not create private emulator storage",
            )
            return false
        }

        publishStatus(EmulatorState.STARTING, "Starting DOSBox Pure…")
        videoReady = false
        videoStatusMessage =
            "DOSBox Pure loaded; waiting for its first visible frame"
        started = nativeStart(
            gameExecutable.absolutePath,
            systemDirectory.absolutePath,
            saveDirectory.absolutePath,
        )
        if (!started) {
            publishStatus(
                EmulatorState.ERROR,
                "Native emulator session could not be started",
            )
        } else if (!resumed.get()) {
            nativeSetPaused(true)
        }
        return started
    }

    fun resume() {
        resumed.set(true)
        synchronized(this) {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED &&
                    track.playState != AudioTrack.PLAYSTATE_PLAYING
                ) {
                    track.play()
                }
            }
        }
        if (started && libraryReady) {
            nativeSetPaused(false)
            publishStatus(
                if (videoReady) EmulatorState.RUNNING else EmulatorState.STARTING,
                videoStatusMessage,
            )
        }
    }

    fun pause() {
        resumed.set(false)
        if (started && libraryReady) {
            nativeSetPaused(true)
        }
        synchronized(this) {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            }
        }
        if (started) {
            publishStatus(EmulatorState.PAUSED, "Emulation paused")
        }
    }

    fun stop() {
        if (libraryReady) {
            nativeStop()
        }
        started = false
        videoReady = false
        videoStatusMessage = "Emulator stopped"
        releaseAudio()
        publishStatus(EmulatorState.IDLE, "Emulator stopped")
    }

    override fun sendKey(keyCode: Int, down: Boolean): Boolean =
        started && libraryReady && nativeSendKey(keyCode, down)

    fun setJoypadButton(button: Int, down: Boolean) {
        if (started && libraryReady) {
            nativeSetJoypadButton(button, down)
        }
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        replaceDisplaySurface(surfaceTexture)
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        displaySurface?.takeIf(Surface::isValid)?.let(::setSurface)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        setSurface(null)
        releaseDisplaySurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
    }

    override fun close() {
        attachedTextureView?.surfaceTextureListener = null
        attachedTextureView = null
        setSurface(null)
        releaseDisplaySurface()
        stop()
    }

    @Suppress("unused")
    private fun onNativeStatus(status: String) {
        when {
            status.startsWith("running:") -> {
                started = true
                videoReady = true
                videoStatusMessage =
                    "DOSBox Pure visible (${status.removePrefix("running:")})"
                publishStatus(
                    EmulatorState.RUNNING,
                    videoStatusMessage,
                )
            }

            status.startsWith("black:") -> {
                videoReady = false
                videoStatusMessage = status.removePrefix("black:")
                publishStatus(EmulatorState.STARTING, videoStatusMessage)
            }

            status.startsWith("video_wait:") -> {
                videoReady = false
                videoStatusMessage = status.removePrefix("video_wait:")
                publishStatus(EmulatorState.STARTING, videoStatusMessage)
            }

            status == "stopped" -> {
                started = false
                videoReady = false
                videoStatusMessage = "DOSBox Pure stopped"
                releaseAudio()
                publishStatus(EmulatorState.IDLE, "DOSBox Pure stopped")
            }

            status.startsWith("error:") -> {
                publishStatus(
                    EmulatorState.ERROR,
                    status.removePrefix("error:").ifBlank {
                        "DOSBox Pure reported an error"
                    },
                )
            }
        }
    }

    @Suppress("unused")
    @Synchronized
    private fun onNativeAudioConfigured(sampleRate: Int) {
        releaseAudio()
        if (sampleRate !in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            publishStatus(
                EmulatorState.ERROR,
                "Unsupported DOS audio rate: $sampleRate Hz",
            )
            return
        }

        val minimumBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) {
            publishStatus(
                EmulatorState.ERROR,
                "Android could not allocate an audio buffer",
            )
            return
        }

        val requestedBuffer = maxOf(
            minimumBuffer * 2,
            sampleRate * BYTES_PER_STEREO_FRAME / 10,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(requestedBuffer)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            publishStatus(
                EmulatorState.ERROR,
                "Android audio output failed to initialize",
            )
            return
        }

        audioTrack = track
        if (resumed.get()) {
            track.play()
        }
    }

    @Suppress("unused")
    @Synchronized
    private fun onNativeAudioSamples(buffer: ByteBuffer, byteCount: Int) {
        val track = audioTrack ?: return
        if (!resumed.get() || track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        buffer.position(0)
        buffer.limit(byteCount.coerceAtMost(buffer.capacity()))
        track.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    private fun releaseAudio() {
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
        }
        audioTrack = null
    }

    private fun setSurface(surface: Surface?) {
        if (libraryReady) {
            nativeSetSurface(surface)
        }
    }

    private fun replaceDisplaySurface(surfaceTexture: SurfaceTexture) {
        setSurface(null)
        releaseDisplaySurface()
        displaySurface = Surface(surfaceTexture)
        setSurface(displaySurface)
    }

    private fun releaseDisplaySurface() {
        displaySurface?.release()
        displaySurface = null
    }

    private fun ensureNativeLibrary(): Boolean {
        if (libraryReady) return true
        libraryReady = nativeLoadResult.value
        return libraryReady
    }

    private fun publishStatus(state: EmulatorState, message: String) {
        mainHandler.post {
            statusListener(EmulatorStatus(state, message))
        }
    }

    private fun File.ensureDirectory(): Boolean =
        isDirectory || (mkdirs() && isDirectory)

    private external fun nativeStart(
        contentPath: String,
        systemDirectory: String,
        saveDirectory: String,
    ): Boolean

    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeStop()
    private external fun nativeSendKey(keyCode: Int, down: Boolean): Boolean
    private external fun nativeSetJoypadButton(button: Int, down: Boolean)

    private companion object {
        const val SYSTEM_DIRECTORY_NAME = "dosbox-system"
        const val SAVE_DIRECTORY_NAME = "dosbox-saves"
        const val BYTES_PER_STEREO_FRAME = 4
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 192_000

        val nativeLoadResult = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching {
                System.loadLibrary("ultima_core")
            }.isSuccess
        }
    }
}
