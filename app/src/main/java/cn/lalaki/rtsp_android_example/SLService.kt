package cn.lalaki.rtsp_android_example

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Paint
import android.media.MediaCodec
import android.os.IBinder
import android.widget.RemoteViews
import android.widget.TextView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationManagerCompat.IMPORTANCE_HIGH
import com.pedro.rtspserver.RtspServer
import cn.lalaki.rtsp_android_example.binder.SLBinder
import cn.lalaki.rtsp_android_example.util.AACAudioRecorder
import cn.lalaki.rtsp_android_example.util.CameraVideoRecorder

class SLService : Service() {
    private var mRtspUrl: String? = null
    private var mNotify: Notification? = null
    private var mNotifyView: RemoteViews? = null
    private var mCameraVideoRecorder: CameraVideoRecorder? = null
    private var mAACAudioRecorder: AACAudioRecorder? = null
    private var mMainApp: MainApp? = null
    private var mNotificationManager: NotificationManager? = null
    private var mLogView: TextView? = null
    private var mRtspUrlView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        val appContext: Context = applicationContext
        if (appContext is MainApp) {
            mMainApp = appContext
        }
        if (mRtspServer == null) {
            mMainApp?.let {
                mRtspServer = RtspServer(it, USE_PORT)
                mRtspServer?.setLogs(true)
                mRtspServer?.setAudioInfo(AACAudioRecorder.SAMPLE_RATE, false)
                mRtspServer?.setAuth("", "")
                mRtspServer?.startServer()
            }
        }
        mRtspUrl = String.format("rtsp://%s:%s", mRtspServer?.serverIp, mRtspServer?.port)
        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mNotifyView = RemoteViews(packageName, R.layout.notify)
        mNotifyView?.setOnClickPendingIntent(
            R.id.tv1,
            PendingIntent.getActivity(
                applicationContext, 1,
                packageManager.getLaunchIntentForPackage(packageName),
                FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
            )
        )
        mMainApp?.let {
            mNotify = NotificationCompat.Builder(it, getString(R.string.channel_id))
                .setContentText(mRtspUrl)
                .setCustomContentView(mNotifyView)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .build()
            NotificationManagerCompat.from(it).createNotificationChannel(
                NotificationChannelCompat.Builder(mNotify!!.channelId, IMPORTANCE_HIGH)
                    .setName(getString(R.string.app_name))
                    .setDescription(getString(R.string.app_name))
                    .setSound(null, null)
                    .setLightsEnabled(false)
                    .setShowBadge(false)
                    .setVibrationEnabled(false)
                    .build()
            )
        }
        startCameraForeground()
    }

    fun running(): Boolean {
        return mCameraVideoRecorder?.mRunning == true
    }

    fun onRestore(rtspUrlView: TextView) {
        showNotify("$mRtspUrl", rtspUrlView, true)
    }

    fun startRecording(logView: TextView, rtspUrlView: TextView, useFrontCamera: Boolean) {
        mLogView = logView
        mRtspUrlView = rtspUrlView
        val mainApp = mMainApp ?: return
        val rtspServer = mRtspServer ?: return

        showNotify("$mRtspUrl", rtspUrlView, true)

        val recorder = CameraVideoRecorder(
            mainApp, logView,
            mainApp.mWidth, mainApp.mHeight,
            useFrontCamera
        )
        mAACAudioRecorder = AACAudioRecorder(mainApp, mBufferInfo)
        recorder.start(rtspServer, mAACAudioRecorder, mBufferInfo)
        mCameraVideoRecorder = recorder
    }

    fun stopRecording() {
        mCameraVideoRecorder?.release()
        mAACAudioRecorder?.release()
        mRtspUrlView?.let { showNotify(getString(R.string.stopped), it, false) }
    }

    private fun startCameraForeground() {
        val notify = mNotify ?: return
        try {
            startForeground(
                NOTIFY_ID, notify,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (_: SecurityException) {}
    }

    private fun showNotify(text: String, tv: TextView, state: Boolean) {
        tv.text = if (state) mRtspUrl else getText(R.string.stopped)
        tv.setTextColor(getColor(if (state) R.color.blue else android.R.color.darker_gray))
        if (state) {
            tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            tv.paintFlags = tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
        mNotifyView?.setTextViewText(R.id.tv1, text)
        mNotifyView?.setTextColor(
            R.id.tv1,
            getColor(if (state) android.R.color.holo_green_dark else android.R.color.darker_gray)
        )
        startCameraForeground()
        if (!state) {
            mNotificationManager?.notify(NOTIFY_ID, mNotify)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return SLBinder(this)
    }

    companion object {
        var mRtspServer: RtspServer? = null
        val mBufferInfo = MediaCodec.BufferInfo()
        const val USE_PORT = 12345
        const val NOTIFY_ID = 1
    }
}
