package com.skoltech.densepose

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** One photo from the gallery. */
class ImageActivity : ViewerActivity(), DensePoseSession.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private var session: DensePoseSession? = null
    private var photo: Bitmap? = null
    private var result: Bitmap? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::load) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.btnPrimary.setText(R.string.pick_photo)
        ui.btnPrimary.setOnClickListener { pick() }
        ui.btnSecondary.setText(R.string.save_png)
        ui.btnSecondary.setOnClickListener { save() }
        ui.chkColors.setOnCheckedChangeListener { _, _ -> process() }

        val given = intent.data
        if (given != null) load(given) else if (savedInstanceState == null) pick()
    }

    private fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    private fun load(uri: Uri) {
        executor.execute {
            try {
                photo = decode(uri)
                process()
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    /** EXIF-rotated, at most DECODE_SIDE px on the long side, in ARGB_8888. */
    private fun decode(uri: Uri): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = DECODE_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun process() {
        val bmp = photo ?: return
        val alpha = colorAlpha
        executor.execute {
            try {
                val s = session ?: DensePoseSession(this, this).also { session = it }
                s.alpha = alpha
                s.submitBitmap(bmp)
            } catch (e: Exception) {
                showError(e)
            }
        }
    }

    override fun onFrame(frame: DensePoseSession.Frame) {
        result = frame.bitmap
        ui.frameView.show(frame.bitmap, frame.info)
        runOnUiThread {
            ui.btnSecondary.visibility = View.VISIBLE
            ui.txtHud.visibility = View.VISIBLE
            ui.txtHud.text = getString(R.string.hud_image, people(frame.info.people), frame.info.latencyMs, frame.backend)
        }
    }

    override fun onError(error: Throwable) = showError(error)

    private fun save() {
        val bmp = result ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "densepose_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DensePose")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return showError(IllegalStateException("cannot create the file"))
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        toast(getString(R.string.saved, "Pictures/DensePose"))
    }

    override fun onDestroy() {
        session?.finish()
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val DECODE_SIDE = 1920
    }
}
