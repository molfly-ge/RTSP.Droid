package cn.lalaki.rtsp_android_example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.pedro.rtspserver.RtspServer
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class CameraVideoRecorder(
    private val context: Context,
    private val logView: TextView,
    private val width: Int,
    private val height: Int,
    private var useFrontCamera: Boolean
) {
    private var mAvcEncoder: MediaCodec? = null
    private var mEncoderSurface: Surface? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mCameraThread: HandlerThread? = null
    private var mCameraHandler: Handler? = null
    var mRunning = false

    fun start(
        rtspServer: RtspServer,
        aacAudioRecorder: AACAudioRecorder?,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        mCameraThread = HandlerThread("CameraThread").also { it.start() }
        mCameraHandler = Handler(mCameraThread!!.looper)

        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        videoFormat.setInteger(
            MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mAvcEncoder = encoder
            encoder.configure(videoFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE, null)
            mEncoderSurface = encoder.createInputSurface()
            encoder.start()
            logView.post { logView.append("Encoder started: ${width}x${height}\n") }
        } catch (e: Exception) {
            logView.post { logView.append("Encoder error: ${e.localizedMessage}\n") }
            return
        }

        openCamera {
            startEncoderLoop(rtspServer, aacAudioRecorder, bufferInfo)
        }
    }

    private fun openCamera(onReady: () -> Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCameraId(cameraManager)
        if (cameraId == null) {
            logView.post { logView.append("No camera found\n") }
            return
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            logView.post { logView.append("Camera permission not granted\n") }
            return
        }

        logView.post { logView.append("Opening camera: $cameraId\n") }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                mCameraDevice = camera
                logView.post { logView.append("Camera opened\n") }
                createCaptureSession(camera, onReady)
            }

            override fun onDisconnected(camera: CameraDevice) {
                logView.post { logView.append("Camera disconnected\n") }
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                logView.post { logView.append("Camera error: $error\n") }
                camera.close()
            }
        }, mCameraHandler)
    }

    private fun findCameraId(cameraManager: CameraManager): String? {
        val targetFacing = if (useFrontCamera)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun createCaptureSession(camera: CameraDevice, onReady: () -> Unit) {
        val surface = mEncoderSurface ?: return
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    mCaptureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    session.setRepeatingRequest(request, null, mCameraHandler)
                    logView.post { logView.append("Camera streaming started\n") }
                    onReady()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logView.post { logView.append("Camera session config failed\n") }
                }
            },
            mCameraHandler
        )
    }

    private fun csd0Handler(data: ByteArray, rtspServer: RtspServer) {
        var spsPosition = -1
        var ppsPosition = -1
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                if (spsPosition == -1) {
                    spsPosition = i
                } else if (ppsPosition == -1) {
                    ppsPosition = i
                }
                i += 4
            } else {
                i++
            }
        }
        if (spsPosition >= 0 && ppsPosition > spsPosition) {
            val spsSize = ppsPosition - spsPosition
            val sps = ByteBuffer.allocate(spsSize).put(data, spsPosition, spsSize)
            val ppsSize = data.size - ppsPosition
            val pps = ByteBuffer.allocate(ppsSize).put(data, ppsPosition, ppsSize)
            rtspServer.setVideoInfo(sps, pps, null)
            logView.post { logView.append("Video info set (SPS/PPS)\n") }
        }
    }

    private fun startEncoderLoop(
        rtspServer: RtspServer,
        aacAudioRecorder: AACAudioRecorder?,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        thread {
            mRunning = true
            try {
                val encoder = mAvcEncoder ?: return@thread
                val beginTime = System.nanoTime()
                while (mRunning) {
                    val timestamp = (System.nanoTime() - beginTime) / 1000L
                    aacAudioRecorder?.sendAacBuffer(rtspServer, timestamp)
                    val index = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    if (index < 0) continue
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        val formatBuffer = encoder.getOutputFormat(index).getByteBuffer("csd-0")
                        if (formatBuffer != null) {
                            csd0Handler(formatBuffer.array(), rtspServer)
                        }
                    }
                    val outputBuffer = encoder.getOutputBuffer(index)
                    if (outputBuffer != null) {
                        bufferInfo.presentationTimeUs = timestamp
                        rtspServer.sendVideo(outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            } catch (e: IllegalStateException) {
                logView.post { logView.append("Encoder: ${e.localizedMessage}\n") }
            }
            stopInternal()
        }
    }

    private fun stopInternal() {
        try { mCaptureSession?.stopRepeating() } catch (_: Exception) {}
        try { mCaptureSession?.close() } catch (_: Exception) {}
        try { mCameraDevice?.close() } catch (_: Exception) {}
        try {
            mAvcEncoder?.stop()
            mAvcEncoder?.release()
        } catch (_: Exception) {}
        mEncoderSurface?.release()
        mCameraThread?.quitSafely()
        mRunning = false
        logView.post { logView.append("Camera recorder released\n") }
    }

    fun release() {
        mRunning = false
    }
}
