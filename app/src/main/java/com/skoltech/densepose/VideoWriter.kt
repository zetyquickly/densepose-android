package com.skoltech.densepose

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

/**
 * Writes the DensePose pictures to Movies/DensePose as H.264 mp4, with the audio of `audioSource` copied over.
 * write() and finish() from one thread.
 */
class VideoWriter(private val context: Context, width: Int, height: Int, fps: Float, private val audioSource: Uri?) {
    private val w = width and 1.inv()
    private val h = height and 1.inv()
    private val resolver = context.contentResolver
    private val uri: Uri
    private val pfd: ParcelFileDescriptor
    private val muxer: MediaMuxer
    private val encoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var audio: MediaExtractor? = null
    private var muxerStarted = false

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "densepose_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DensePose")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("cannot create the output file")
        pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("cannot open the output file")
        muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.12f).toInt().coerceIn(1_000_000, 12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        if (audioSource != null) {
            try {
                val ex = MediaExtractor().apply { setDataSource(context, audioSource, null) }
                val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                if (t != null) {
                    ex.selectTrack(t)
                    audio = ex
                } else {
                    ex.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "no audio", e)
            }
        }
    }

    /** Pictures must keep the size the writer was made for (rounded down to even). */
    fun write(bitmap: Bitmap, ptsUs: Long) {
        require(bitmap.width >= w && bitmap.height >= h)
        while (true) {
            val i = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (i < 0) {
                drain()
                continue
            }
            val image = encoder.getInputImage(i) ?: throw IllegalStateException("the encoder takes no YUV image")
            val (y, u, v) = image.planes
            NativeUtil.nativeBitmapToYuv(bitmap, y.buffer, u.buffer, v.buffer, y.rowStride, u.rowStride, u.pixelStride)
            encoder.queueInputBuffer(i, 0, w * h * 3 / 2, ptsUs, 0)
            break
        }
        drain()
    }

    /** Ends the video; returns the file (in the gallery). */
    fun finish(): Uri {
        try {
            while (true) {
                val i = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (i >= 0) {
                    encoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain()
            }
            while (!drain()) Unit
            copyAudio()
            if (muxerStarted) muxer.stop()
        } finally {
            encoder.release()
            muxer.release()
            audio?.release()
            pfd.close()
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Moves finished packets to the muxer; true once the end of the stream came out. */
    private fun drain(): Boolean {
        while (true) {
            val o = encoder.dequeueOutputBuffer(info, 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrack = muxer.addTrack(encoder.outputFormat)
                    audio?.let {
                        try {
                            audioTrack = muxer.addTrack(it.getTrackFormat(it.sampleTrackIndex))
                        } catch (e: IllegalArgumentException) {  // a codec mp4 cannot hold: silent video
                            Log.w(TAG, "audio not copied", e)
                        }
                    }
                    muxer.start()
                    muxerStarted = true
                }
                o >= 0 -> {
                    val data = encoder.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && muxerStarted)
                        muxer.writeSampleData(videoTrack, data, info)
                    encoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private fun copyAudio() {
        val ex = audio ?: return
        if (!muxerStarted || audioTrack < 0) return
        val buffer = ByteBuffer.allocate(AUDIO_CHUNK)
        val i = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val n = ex.readSampleData(buffer, 0)
            if (n < 0) break
            i.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(audioTrack, buffer, i)
            ex.advance()
        }
    }

    private companion object {
        const val TAG = "VideoWriter"
        const val TIMEOUT_US = 10_000L
        const val AUDIO_CHUNK = 1 shl 18
    }
}
