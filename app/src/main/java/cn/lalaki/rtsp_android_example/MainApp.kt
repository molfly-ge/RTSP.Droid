package cn.lalaki.rtsp_android_example

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import cn.lalaki.rtsp_android_example.binder.SLBinder
import cn.lalaki.rtsp_android_example.ui.MainActivity
import com.pedro.common.ConnectChecker

class MainApp : Application(), ServiceConnection, ConnectChecker {
    var mService: SLService? = null
    var mActivity: MainActivity? = null
    var mWidth = 1280
    var mHeight = 720
    val mRequestPermissions = arrayOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.CAMERA
    )

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val slService = (service as SLBinder).mContext
        mService = slService
        mActivity?.onServiceReady(slService)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        mService = null
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onDisconnect() {}
    override fun onNewBitrate(bitrate: Long) {}
}
