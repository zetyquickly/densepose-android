package com.skoltech.densepose

import android.Manifest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** The camera stream through the pipeline: what is shown is the camera frame with the vertex colours drawn on it. */
class LiveActivity : ViewerActivity(), DensePoseSession.Listener {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var session: DensePoseSession? = null
    private var front = false
    private val fpsMeter = FpsMeter()
    private val cameraMeter = FpsMeter()

    @Volatile
    private var cameraFps = 0f

    @Volatile
    private var failed = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else toast(getString(R.string.camera_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.btnPrimary.setText(R.string.flip_camera)
        ui.btnPrimary.setOnClickListener {
            front = !front
            ui.frameView.mirror = front
            startCamera()
        }
        ui.chkColors.setOnCheckedChangeListener { _, _ -> session?.alpha = colorAlpha }
        ui.txtHud.visibility = View.VISIBLE
        ui.txtHud.text = getString(R.string.hud_starting)
        permission.launch(Manifest.permission.CAMERA)
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val s = session ?: DensePoseSession(this, this).also { session = it }
            s.alpha = colorAlpha

            val builder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Settings.quality(this).camera, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
            // sensor frames, whether or not the analyzer was free to take them
            Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    cameraFps = cameraMeter.tick()
                }
            })
            val analysis = builder.build()
            analysis.setAnalyzer(analysisExecutor) { image ->
                try {
                    if (!failed) {
                        val plane = image.planes[0]
                        s.submitRgba(plane.buffer, image.width, image.height, plane.rowStride, image.imageInfo.rotationDegrees)
                    }
                } catch (e: Exception) {  // say it once, not for every frame
                    failed = true
                    showError(e)
                } finally {
                    image.close()
                }
            }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val provider = future.get()
            provider.unbindAll()
            try {
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                showError(e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onFrame(frame: DensePoseSession.Frame) {
        ui.frameView.show(frame.bitmap, frame.info)
        val fps = fpsMeter.tick()
        runOnUiThread {
            ui.txtHud.text = getString(R.string.hud_live, fps, cameraFps, frame.info.latencyMs, people(frame.info.people), frame.backend)
        }
    }

    override fun onError(error: Throwable) = showError(error)

    override fun onDestroy() {
        session?.finish()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
