package com.skoltech.densepose

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread

/**
 * A video from the gallery, run through the pipeline frame by frame, optionally saved to Movies/DensePose with its audio.
 * Without saving, playback follows the video's timestamps when the pipeline is faster than real time.
 */
class VideoActivity : ViewerActivity() {
    private var run: Run? = null

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::start) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.chkSave.visibility = View.VISIBLE
        ui.chkSave.isChecked = intent.getBooleanExtra(EXTRA_SAVE, false)
        ui.progress.visibility = View.VISIBLE
        ui.btnPrimary.setText(R.string.pick_video)
        ui.btnPrimary.setOnClickListener { pick() }
        ui.btnSecondary.setText(R.string.stop)
        ui.btnSecondary.setOnClickListener { stop() }
        ui.chkColors.setOnCheckedChangeListener { _, _ -> run?.session?.alpha = colorAlpha }

        val given = intent.data
        if (given != null && savedInstanceState == null) start(given) else if (savedInstanceState == null) pick()
    }

    private fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    private fun start(uri: Uri) {
        stop()
        run = Run(uri, ui.chkSave.isChecked).also { it.start() }
        ui.btnSecondary.visibility = View.VISIBLE
        ui.chkSave.isEnabled = false
        ui.progress.progress = 0
    }

    private fun stop() {
        run?.cancel()
        run = null
        ui.btnSecondary.visibility = View.GONE
        ui.chkSave.isEnabled = true
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    /** One video from decoding to its last result; a later run never touches the views of an earlier one. */
    private inner class Run(private val uri: Uri, private val save: Boolean) : DensePoseSession.Listener {
        val session = DensePoseSession(this@VideoActivity, this).also { it.alpha = colorAlpha }
        private val decoder = VideoDecoder(this@VideoActivity, uri, session)
        private val fpsMeter = FpsMeter()

        @Volatile
        private var cancelled = false

        // result thread; writer is also finished from the decoder thread
        private val lock = Any()
        private var info: VideoDecoder.Info? = null
        private var writer: VideoWriter? = null
        private var firstPts = -1L
        private var startedAt = 0L

        fun start() {
            thread(name = "fnl-video") {
                try {
                    info = decoder.probe()
                    decoder.run()
                } catch (e: Exception) {
                    onError(e)
                    session.finish()
                } finally {
                    if (cancelled) closeWriter()
                }
            }
        }

        /** Returns at once; the consumer thread finishes what is in flight, so a half-saved video stays valid. */
        fun cancel() {
            cancelled = true
            decoder.cancel()
            session.finish()
        }

        override fun onFrame(frame: DensePoseSession.Frame) {
            if (cancelled) return
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                if (firstPts < 0) {
                    firstPts = frame.ptsUs
                    startedAt = now
                    val i = info
                    if (save && i != null)
                        writer = VideoWriter(this@VideoActivity, frame.bitmap.width, frame.bitmap.height, i.fps, uri.takeIf { i.hasAudio })
                }
                val w = writer
                if (w != null) {
                    w.write(frame.bitmap, frame.ptsUs)
                } else {
                    val wait = startedAt + (frame.ptsUs - firstPts) / 1000 - now
                    if (wait > 0) Thread.sleep(wait)
                }
            }
            ui.frameView.show(frame.bitmap, frame.info)

            val fps = fpsMeter.tick()
            val duration = info?.durationUs ?: 0
            runOnUiThread {
                if (cancelled) return@runOnUiThread
                ui.txtHud.visibility = View.VISIBLE
                ui.txtHud.text = getString(R.string.hud_video, fps, frame.info.latencyMs, people(frame.info.people), frame.backend)
                if (duration > 0) ui.progress.progress = (ui.progress.max * frame.ptsUs / duration).toInt()
            }
        }

        override fun onFinished() {
            val saved = closeWriter()
            if (cancelled) return
            runOnUiThread {
                ui.progress.progress = ui.progress.max
                ui.btnSecondary.visibility = View.GONE
                ui.chkSave.isEnabled = true
            }
            if (saved != null) toast(getString(R.string.saved, "Movies/DensePose"), Toast.LENGTH_LONG)
        }

        override fun onError(error: Throwable) {
            if (!cancelled) showError(error)
        }

        private fun closeWriter(): Uri? = synchronized(lock) {
            val w = writer ?: return null
            writer = null
            try {
                w.finish()
            } catch (e: Exception) {
                onError(e)
                null
            }
        }
    }

    companion object {
        /** Boolean extra: start with "Save mp4" ticked. */
        const val EXTRA_SAVE = "save"
    }
}
