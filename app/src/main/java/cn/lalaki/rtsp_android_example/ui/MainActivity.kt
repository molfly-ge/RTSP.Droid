package cn.lalaki.rtsp_android_example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import cn.lalaki.rtsp_android_example.MainApp
import cn.lalaki.rtsp_android_example.R
import cn.lalaki.rtsp_android_example.SLService
import cn.lalaki.rtsp_android_example.databinding.MainBinding
import com.suke.widget.SwitchButton
import java.net.URISyntaxException
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), View.OnClickListener,
    SwitchButton.OnCheckedChangeListener {
    lateinit var mBinding: MainBinding
    private lateinit var mMainApp: MainApp
    private var mUseFrontCamera = false
    private var mServiceBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        if (appContext is MainApp) {
            mMainApp = appContext
            mMainApp.mActivity = this
        }
        mBinding = MainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        mBinding.clearAll.setOnClickListener(this)
        mBinding.forceExit.setOnClickListener(this)
        mBinding.copyBtn.setOnClickListener(this)
        mBinding.report.setOnClickListener(this)
        mBinding.switchCamera.setOnClickListener(this)

        val service = mMainApp.mService
        if (service != null && service.running()) {
            service.onRestore(mBinding.rtspUrl)
            mBinding.switchBtn.isChecked = true
        }

        mBinding.switchBtn.setOnCheckedChangeListener(this)
        ActivityCompat.requestPermissions(this, mMainApp.mRequestPermissions, 0x233)

        mBinding.forceExit.buttonColor =
            getColor(info.hoang8f.fbutton.R.color.fbutton_color_alizarin)
        mBinding.report.buttonColor = getColor(info.hoang8f.fbutton.R.color.fbutton_color_green_sea)
        mBinding.pixel.adapter = ArrayAdapter(
            this, R.layout.item,
            resources.getStringArray(R.array.pixels)
        )
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu1, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.report -> startUrl(R.string.issues_url)
            R.id.copy_btn -> {
                if (mBinding.rtspUrl.text.contains("rtsp")) {
                    val service = getSystemService(CLIPBOARD_SERVICE)
                    if (service is ClipboardManager) {
                        service.setPrimaryClip(
                            ClipData.newPlainText(getText(R.string.app_name), mBinding.rtspUrl.text)
                        )
                        Toast.makeText(this, R.string.copy_ok, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            R.id.clear_all -> mBinding.logView.text = ""
            R.id.force_exit -> exitProcess(0)
            R.id.switch_camera -> {
                mUseFrontCamera = !mUseFrontCamera
                mBinding.logView.append(
                    if (mUseFrontCamera) "Switched to front camera\n" else "Switched to back camera\n"
                )
                val service = mMainApp.mService
                if (service != null && service.running()) {
                    service.stopRecording()
                    startRecording(service)
                }
            }
        }
    }

    private fun startUrl(urlId: Int) {
        try {
            startActivity(Intent.getIntentOld(getString(urlId)))
        } catch (_: URISyntaxException) {}
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.opensource) startUrl(R.string.project_url)
        return super.onOptionsItemSelected(item)
    }

    override fun onCheckedChanged(view: SwitchButton?, isChecked: Boolean) {
        mBinding.switchLabel.text =
            getText(if (isChecked) R.string.switch_text_stop else R.string.switch_text)
        if (isChecked) {
            val pixelArray = mBinding.pixel.selectedItem.toString().split("x")
            mMainApp.mWidth = pixelArray[0].toInt()
            mMainApp.mHeight = pixelArray[1].toInt()

            if (!mServiceBound) {
                bindService(
                    Intent(mMainApp, SLService::class.java),
                    mMainApp, BIND_AUTO_CREATE
                )
                mServiceBound = true
            } else {
                mMainApp.mService?.let { startRecording(it) }
            }
        } else {
            mMainApp.mService?.stopRecording()
        }
    }

    fun onServiceReady(service: SLService) {
        if (mBinding.switchBtn.isChecked) {
            startRecording(service)
        }
    }

    private fun startRecording(service: SLService) {
        service.startRecording(mBinding.logView, mBinding.rtspUrl, mUseFrontCamera)
        mBinding.logView.append("Recording started...\n")
    }
}
