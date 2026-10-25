package com.skoltech.densepose

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/**
 * Decodes the first video track of `uri` with MediaCodec (YUV frames in memory) and feeds every frame to the session.
 * run() blocks until the end of the video or cancel(); backpressure comes from the session (submit blocks while the
 * pipeline is full).
 */
class VideoDecoder(private val context: Context, private val uri: Uri, private val session: DensePoseSession) {
    class Info(val fps: Float, val durationUs: Long, val hasAudio: Boolean)

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun probe(): Info {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val (_, f) = videoTrack(ex)
            val audio = (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            return Info(
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() else 30f,
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L,
                audio,
            )
        } finally {
            ex.release()
        }
    }

    /** Frames are delivered through the session; the session is finish()ed at the end of the video (not on cancel). */
    fun run() {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val (track, format) = videoTrack(ex)
            ex.selectTrack(track)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var bt709 = format.getInteger(MediaFormat.KEY_HEIGHT) >= 720  // until the decoder says
            var fullRange = false
            while (!cancelled) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_COLOR_STANDARD))
                        bt709 = f.getInteger(MediaFormat.KEY_COLOR_STANDARD) !in BT601
                    if (f.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = f.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
                } else if (o >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && !cancelled) {
                        val image = codec.getOutputImage(o) ?: throw IllegalStateException("the decoder gave no YUV image")
                        try {
                            session.submitYuv(image, rotation, bt709, fullRange, info.presentationTimeUs)
                        } finally {
                            image.close()
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (eos) break
                }
            }
            if (!cancelled) session.finish()
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "codec stop", e)
            }
            codec?.release()
            ex.release()
        }
    }

    private fun videoTrack(ex: MediaExtractor): Pair<Int, MediaFormat> {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) return i to f
        }
        throw IllegalArgumentException("no video track")
    }

    private companion object {
        const val TAG = "VideoDecoder"
        val BT601 = setOf(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL)
        const val TIMEOUT_US = 10_000L
    }
}
