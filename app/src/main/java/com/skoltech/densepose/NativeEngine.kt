package com.skoltech.densepose

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** One finished frame: size of the working picture, timings, and the people found (frame px). */
class FrameInfo(
    val id: Long,
    val width: Int,
    val height: Int,
    val latencyMs: Double,
    /** x1, y1, x2, y2, score per person. */
    val boxes: FloatArray,
) {
    val people get() = boxes.size / 5
}

/**
 * The CSE DensePose pipeline of export/engine (TFLite graphs A, B1, B2 on the GPU, C on the CPU).
 *
 * Frames go in through submit*() and come out of next() in order, composited with the vertex colours; render() copies the
 * picture of the last next() into a bitmap. submit* blocks while the pipeline is full. close() ends the input; destroy() frees
 * the engine and must come after the thread calling next() has seen it return null.
 */
class NativeEngine(modelDir: String, gpu: Boolean, cpuThreads: Int, scoreThreshold: Float, vertexColors: ByteArray, maxSide: Int) {
    private val handle = nativeCreate(modelDir, gpu, cpuThreads, scoreThreshold, vertexColors, maxSide)

    fun submitRgba(id: Long, buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int) =
        nativeSubmitRgba(handle, id, buffer, width, height, rowStride, rotation)

    fun submitBitmap(id: Long, bitmap: Bitmap, rotation: Int = 0) = nativeSubmitBitmap(handle, id, bitmap, rotation)

    fun submitYuv(
        id: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int, yStride: Int, uvStride: Int,
        uvPixelStride: Int, rotation: Int, bt709: Boolean, fullRange: Boolean,
    ) = nativeSubmitYuv(handle, id, y, u, v, width, height, yStride, uvStride, uvPixelStride, rotation, if (bt709) 1 else 0, fullRange)

    fun close() = nativeClose(handle)

    /** Blocks for the next result; null once closed and drained. */
    fun next(alpha: Float): FrameInfo? {
        val r = nativeNext(handle, alpha) ?: return null
        return FrameInfo(r[0].toLong(), r[1].toInt(), r[2].toInt(), r[3], FloatArray(r[4].toInt() * 5) { r[5 + it].toFloat() })
    }

    fun render(bitmap: Bitmap) = nativeRender(handle, bitmap)

    fun destroy() = nativeDestroy(handle)

    private external fun nativeCreate(modelDir: String, gpu: Boolean, cpuThreads: Int, scoreThreshold: Float, colors: ByteArray, maxSide: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSubmitRgba(handle: Long, id: Long, buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, rotation: Int): IntArray
    private external fun nativeSubmitBitmap(handle: Long, id: Long, bitmap: Bitmap, rotation: Int): IntArray
    private external fun nativeSubmitYuv(
        handle: Long, id: Long, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, width: Int, height: Int, yStride: Int, uvStride: Int,
        uvPixelStride: Int, rotation: Int, standard: Int, fullRange: Boolean,
    ): IntArray
    private external fun nativeClose(handle: Long)
    private external fun nativeNext(handle: Long, alpha: Float): DoubleArray?
    private external fun nativeRender(handle: Long, bitmap: Bitmap)

    companion object {
        init {
            System.loadLibrary("fnl_jni")
        }
    }
}

object NativeUtil {
    init {
        System.loadLibrary("fnl_jni")
    }

    /** Bitmap -> the planes of a MediaCodec input Image (BT.709, limited range). */
    @JvmStatic
    external fun nativeBitmapToYuv(bitmap: Bitmap, y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, yStride: Int, uvStride: Int, uvPixelStride: Int)
}
