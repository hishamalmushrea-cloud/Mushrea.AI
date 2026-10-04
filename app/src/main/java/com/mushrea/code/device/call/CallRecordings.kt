package com.mushrea.code.device.call

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.mushrea.code.device.usb.AdbException
import java.io.File

/**
 * Process-wide near-end recorder used by [CallRecordService] and the call-agent executor.
 *
 * Audio source is [MediaRecorder.AudioSource.VOICE_COMMUNICATION] (this phone's microphone with
 * echo cancellation), falling back to [MediaRecorder.AudioSource.MIC]. The downlink of the other
 * party is a privileged Android API and is never requested.
 */
object CallRecordings {
    data class Snapshot(
        val recording: Boolean,
        val path: String? = null,
        val bytes: Long = 0,
        val startedAtMillis: Long? = null,
        val source: String? = null,
        val error: String? = null,
    )

    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtMillis: Long? = null
    private var source: String? = null
    private var lastError: String? = null

    fun snapshot(): Snapshot =
        synchronized(lock) {
            val current = file
            Snapshot(
                recording = recorder != null,
                path = current?.absolutePath,
                bytes = current?.length() ?: 0L,
                startedAtMillis = startedAtMillis,
                source = source,
                error = lastError,
            )
        }

    fun start(
        context: Context,
        destination: File,
    ): Snapshot =
        synchronized(lock) {
            if (recorder != null) {
                throw AdbException("a call recording is already in progress — stop it first")
            }
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
            val attempt =
                startWithSource(context, destination, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "voice-communication")
                    ?: startWithSource(context, destination, MediaRecorder.AudioSource.MIC, "microphone")
            if (attempt == null) {
                lastError = "MediaRecorder refused both voice-communication and microphone sources"
                throw AdbException(lastError!!)
            }
            recorder = attempt.first
            source = attempt.second
            file = destination
            startedAtMillis = System.currentTimeMillis()
            lastError = null
            snapshotUnlocked()
        }

    fun stop(): Snapshot =
        synchronized(lock) {
            val current = recorder
            val dest = file
            if (current == null) {
                return Snapshot(recording = false, error = lastError ?: "no call recording is in progress")
            }
            val stopped =
                runCatching {
                    current.stop()
                    current.reset()
                    current.release()
                }
            recorder = null
            startedAtMillis = null
            source = null
            file = null
            if (stopped.isFailure) {
                lastError = stopped.exceptionOrNull()?.message ?: "MediaRecorder failed to stop"
                dest?.delete()
                return Snapshot(recording = false, error = lastError)
            }
            lastError = null
            Snapshot(
                recording = false,
                path = dest?.absolutePath,
                bytes = dest?.length() ?: 0L,
            )
        }

    private fun snapshotUnlocked(): Snapshot {
        val current = file
        return Snapshot(
            recording = recorder != null,
            path = current?.absolutePath,
            bytes = current?.length() ?: 0L,
            startedAtMillis = startedAtMillis,
            source = source,
            error = lastError,
        )
    }

    private fun startWithSource(
        context: Context,
        destination: File,
        audioSource: Int,
        label: String,
    ): Pair<MediaRecorder, String>? {
        val media =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
        return runCatching {
            media.setAudioSource(audioSource)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioSamplingRate(44_100)
            media.setAudioEncodingBitRate(128_000)
            media.setOutputFile(destination.absolutePath)
            media.prepare()
            media.start()
            media to label
        }.getOrElse {
            runCatching { media.reset() }
            runCatching { media.release() }
            destination.delete()
            null
        }
    }
}
