package com.skoltech.densepose

import android.content.Context
import android.util.Size
import java.io.File

/**
 * A network size. Each has a landscape graph set and a portrait twin, because the engine center-crops to the network's aspect
 * ratio. `maxSide` is the longest side of the frames fed to it, `camera` what the live view asks the camera for.
 */
enum class Quality(val landscape: String, val portrait: String, val maxSide: Int, val camera: Size) {
    FAST("cse", "cse_portrait", 960, Size(640, 480)),
    HIGH("cse_800", "cse_800_portrait", 1088, Size(1280, 960)),
}

/** The graph sets bundled in assets/models/<name>/ (see syncModels in app/build.gradle), vertex colours, and settings. */
object ModelStore {

    /** TFLite maps the graphs from a file, so the assets are copied out once (again when their size changes). */
    fun modelDir(context: Context, name: String): String {
        val dst = File(context.filesDir, "models/$name").apply { mkdirs() }
        for (file in context.assets.list("models/$name").orEmpty()) {
            val out = File(dst, file)
            val size = context.assets.openFd("models/$name/$file").use { it.length }
            if (out.length() == size) continue
            context.assets.open("models/$name/$file").use { src -> out.outputStream().use { src.copyTo(it) } }
        }
        return dst.absolutePath
    }

    /** RGB8 per SMPL vertex (tools/make_vertex_colors.py). */
    fun vertexColors(context: Context): ByteArray = context.assets.open("vertex_colors.bin").use { it.readBytes() }

    fun hasModel(context: Context, name: String) = context.assets.list("models/$name").orEmpty().isNotEmpty()

    fun isBundled(context: Context, quality: Quality) = hasModel(context, quality.landscape)
}

object Settings {
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Graphs A, B1, B2 on the GPU delegate (else XNNPACK on the CPU). */
    fun useGpu(context: Context) = prefs(context).getBoolean("gpu", true)

    fun setUseGpu(context: Context, value: Boolean) = prefs(context).edit().putBoolean("gpu", value).apply()

    /** The chosen network size; FAST when the chosen one is not in this APK. */
    fun quality(context: Context): Quality {
        val q = Quality.entries.firstOrNull { it.name == prefs(context).getString("quality", null) } ?: Quality.FAST
        return if (ModelStore.isBundled(context, q)) q else Quality.FAST
    }

    fun setQuality(context: Context, value: Quality) = prefs(context).edit().putString("quality", value.name).apply()
}
