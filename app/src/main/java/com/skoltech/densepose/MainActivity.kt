package com.skoltech.densepose

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.skoltech.densepose.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)

        ui.btnPhoto.setOnClickListener { startActivity(Intent(this, ImageActivity::class.java)) }
        ui.btnVideo.setOnClickListener { startActivity(Intent(this, VideoActivity::class.java)) }
        ui.btnLive.setOnClickListener { startActivity(Intent(this, LiveActivity::class.java)) }
        ui.radioHigh.isEnabled = ModelStore.isBundled(this, Quality.HIGH)
        (if (Settings.quality(this) == Quality.HIGH) ui.radioHigh else ui.radioFast).isChecked = true
        ui.quality.setOnCheckedChangeListener { _, id ->
            Settings.setQuality(this, if (id == R.id.radio_high) Quality.HIGH else Quality.FAST)
        }
        ui.chkGpu.isChecked = Settings.useGpu(this)
        ui.chkGpu.setOnCheckedChangeListener { _, checked -> Settings.setUseGpu(this, checked) }
    }
}
