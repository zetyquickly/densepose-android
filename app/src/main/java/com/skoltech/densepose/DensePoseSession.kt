package com.skoltech.densepose

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Frames in (from the camera, a bitmap, a video decoder), DensePose pictures out.
 *
 * One producer thread calls the submit*() functions; results are delivered, in order, on a consumer thread owned by the
 * session. The network is landscape or portrait: the engine center-crops to the network's aspect ratio, so the matching
 * graphs are picked from the upright shape of each frame and swapped when it changes.
 */
class DensePoseSession(context: Context, private val listener: Listener) {
    interface Listener {
        /** Consumer thread. `frame.bitmap` is reused after a few more frames. */
        fun onFrame(frame: Frame)

        /** After finish(), once every submitted frame has been delivered. */
        fun onFinished() = Unit

        fun onError(error: Throwable)
    }

    class Frame(val bitmap: Bitmap, val info: FrameInfo, val ptsUs: Long, val gpu: Boolean) {
        val backend get() = if (gpu) "GPU" else "CPU"
    }

    /** Blend of the vertex colours over the person pixels: 0 shows the plain frame. */
    @Volatile
    var alpha = DEFAULT_ALPHA

    private val appContext = context.applicationContext
    private val quality = Settings.quality(appContext)
    private val nextId = AtomicLong()

    @Volatile
    private var worker: Worker? = null

    @Volatile
    private var finishing = false

    fun submitRgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int, ptsUs: Long = 0): Boolean =
        workerFor(isPortrait(width, height, rotation))?.submit(ptsUs) { e, id -> e.submitRgba(id, buffer, width, height, rowStride, rotation) } ?: false

    fun submitBitmap(bitmap: Bitmap, ptsUs: Long = 0): Boolean =
        workerFor(isPortrait(bitmap.width, bitmap.height, 0))?.submit(ptsUs) { e, id -> e.submitBitmap(id, bitmap) } ?: false

    /** A YUV_420_888 image from MediaCodec (the pixels are converted before this returns). */
    fun submitYuv(image: Image, rotation: Int, bt709: Boolean, fullRange: Boolean, ptsUs: Long): Boolean {
        val (y, u, v) = image.planes
        return workerFor(isPortrait(image.width, image.height, rotation))?.submit(ptsUs) { e, id ->
            e.submitYuv(id, y.buffer, u.buffer, v.buffer, image.width, image.height, y.rowStride, u.rowStride, u.pixelStride, rotation, bt709, fullRange)
        } ?: false
    }

    /** No more frames: the pipeline drains, then onFinished(). */
    fun finish() {
        finishing = true
        worker?.close()
    }

    private fun isPortrait(width: Int, height: Int, rotation: Int) = if (rotation % 180 == 0) height > width else width > height

    private fun workerFor(portrait: Boolean): Worker? {
        if (finishing) return null
        var w = worker
        if (w != null && w.portrait == portrait) return w
        w?.close()  // drains on its own thread
        w = Worker(portrait)
        worker = w
        if (finishing) w.close()  // finish() came while the graphs were loading
        return w
    }

    private inner class Worker(val portrait: Boolean) {
        private val lock = Any()
        private val ptsById = ConcurrentHashMap<Long, Long>()
        private val engine: NativeEngine
        private val gpu: Boolean

        @Volatile
        private var closing = false
        private var destroyed = false

        init {
            // a build that bundles only the landscape graphs crops portrait frames to it
            val dir = ModelStore.modelDir(appContext, if (portrait && ModelStore.hasModel(appContext, quality.portrait)) quality.portrait else quality.landscape)
            val colors = ModelStore.vertexColors(appContext)
            var onGpu = Settings.useGpu(appContext)
            engine = try {
                NativeEngine(dir, onGpu, CPU_THREADS, SCORE_THRESHOLD, colors, quality.maxSide)
            } catch (e: RuntimeException) {
                if (!onGpu) throw e
                Log.w(TAG, "GPU delegate failed (${e.message}), falling back to the CPU")
                onGpu = false
                NativeEngine(dir, false, CPU_THREADS, SCORE_THRESHOLD, colors, quality.maxSide)
            }
            gpu = onGpu
            thread(name = "fnl-results") { consume() }
        }

        fun submit(ptsUs: Long, send: (NativeEngine, Long) -> Unit): Boolean = synchronized(lock) {
            if (closing || destroyed) return false
            val id = nextId.getAndIncrement()
            ptsById[id] = ptsUs
            send(engine, id)
            true
        }

        /** Not under `lock`: a submit blocked on a full pipeline is released by it. */
        fun close() {
            closing = true
            engine.close()
        }

        private fun consume() {
            val ring = arrayOfNulls<Bitmap>(RING)
            var slot = 0
            try {
                while (true) {
                    val info = engine.next(alpha) ?: break
                    var bmp = ring[slot]
                    if (bmp == null || bmp.width != info.width || bmp.height != info.height) {
                        bmp = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
                        ring[slot] = bmp
                    }
                    slot = (slot + 1) % RING
                    engine.render(bmp)
                    listener.onFrame(Frame(bmp, info, ptsById.remove(info.id) ?: 0, gpu))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pipeline failed", t)
                listener.onError(t)
            } finally {
                synchronized(lock) {
                    destroyed = true
                    engine.destroy()
                }
                if (finishing && worker === this) listener.onFinished()
            }
        }
    }

    companion object {
        const val DEFAULT_ALPHA = 0.65f
        private const val TAG = "DensePoseSession"
        private const val CPU_THREADS = 4
        private const val SCORE_THRESHOLD = 0.5f
        private const val RING = 4
    }
}
