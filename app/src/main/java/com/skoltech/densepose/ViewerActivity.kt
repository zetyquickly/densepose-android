package com.skoltech.densepose

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.skoltech.densepose.databinding.ActivityViewerBinding

/** A frame view with the Colors / Boxes toggles and an HUD, shared by the photo, video and live screens. */
abstract class ViewerActivity : AppCompatActivity() {
    protected lateinit var ui: ActivityViewerBinding

    protected val colorAlpha get() = if (ui.chkColors.isChecked) DensePoseSession.DEFAULT_ALPHA else 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.chkBoxes.setOnCheckedChangeListener { _, checked -> ui.frameView.showBoxes = checked }
    }

    protected fun people(count: Int): String = resources.getQuantityString(R.plurals.people, count, count)

    /** Any thread. */
    protected fun showError(error: Throwable) = toast(getString(R.string.error, error.message), Toast.LENGTH_LONG)

    protected fun toast(text: String, length: Int = Toast.LENGTH_SHORT) = runOnUiThread { Toast.makeText(this, text, length).show() }
}
