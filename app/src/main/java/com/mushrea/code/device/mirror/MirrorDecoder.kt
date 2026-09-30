package com.mushrea.code.device.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Decodes the mirrored H.264 access units onto a Surface as fast as they arrive.
 *
 * Config units are fed flagged as codec data, frames carry a key-frame flag so the decoder can
 * start on the first one. Frames are dropped (not queued forever) when the decoder falls behind:
 * a live mirror is allowed to skip, and the stream resynchronizes at the next keyframe.
 */
class MirrorDecoder {
    private var codec: MediaCodec? = null
    private val queue = ArrayBlockingQueue<AccessUnit>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    /** Latest decoder-side failure, read by the session to report an honest state. */
    @Volatile
    var failure: String? = null
        private set

    /** Prepares decoding onto [surface]; any previous decoder is released first. */
    fun begin(surface: Surface) {
        release()
        val format =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        val newCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, surface, null, 0)
        newCodec.start()
        codec = newCodec
        running = true
        thread(isDaemon = true, name = "mirror-decode") { drain(newCodec) }
    }

    /** Offers one access unit; returns false when the queue is full and the unit was dropped. */
    fun submit(unit: AccessUnit): Boolean = queue.offer(unit)

    fun release() {
        running = false
        val old = codec
        codec = null
        queue.clear()
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    private fun drain(activeCodec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val unit = queue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                val index = activeCodec.dequeueInputBuffer(POLL_MS * 1000)
                if (index < 0) continue
                val buffer = activeCodec.getInputBuffer(index) ?: continue
                buffer.clear()
                val size = minOf(unit.data.size, buffer.capacity())
                if (size < unit.data.size) continue
                buffer.put(unit.data, 0, size)
                var flags = 0
                if (unit.isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                if (unit.isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                activeCodec.queueInputBuffer(index, 0, size, System.nanoTime() / 1000, flags)
                var out = activeCodec.dequeueOutputBuffer(info, 0)
                while (out >= 0) {
                    activeCodec.releaseOutputBuffer(out, true)
                    out = activeCodec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (t: Throwable) {
            if (running) failure = t.message ?: t.javaClass.simpleName
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val POLL_MS = 50L
        const val DEFAULT_WIDTH = 854
        const val DEFAULT_HEIGHT = 480
    }
}
