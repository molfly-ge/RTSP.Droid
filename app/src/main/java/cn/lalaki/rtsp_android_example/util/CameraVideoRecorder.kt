package cn.lalaki.rtsp_android_example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.pedro.rtspserver.RtspServer
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.concurrent.thread

class CameraVideoRecorder(
    private val context: Context,
    private val logView: TextView,
    private val requestedWidth: Int,
    private val requestedHeight: Int,
    private var useFrontCamera: Boolean
) {
    private var mAvcEncoder: MediaCodec? = null
    private var mEncoderSurface: Surface? = null
    private var mCameraDevice: CameraDevice? = null
    private var mCaptureSession: CameraCaptureSession? = null
    private var mCameraThread: HandlerThread? = null
    private var mCameraHandler: Handler? = null
    private var mVideoInfoSet = false
    private var mActualWidth = requestedWidth
    private var mActualHeight = requestedHeight
    var mRunning = false

    fun start(
        rtspServer: RtspServer,
        aacAudioRecorder: AACAudioRecorder?,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        mCameraThread = HandlerThread("CameraThread").also { it.start() }
        mCameraHandler = Handler(mCameraThread!!.looper)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findCameraId(cameraManager)
        if (cameraId == null) {
            logView.post { logView.append("No camera found\n") }
            cleanup()
            return
        }

        val bestSize = findBestSize(cameraManager, cameraId)
        mActualWidth = bestSize.width
        mActualHeight = bestSize.height
        logView.post { logView.append("Resolution: ${mActualWidth}x${mActualHeight}\n") }

        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, mActualWidth, mActualHeight
        )
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
            logView.post { logView.append("Encoder started\n") }
        } catch (e: Exception) {
            logView.post { logView.append("Encoder error: ${e.localizedMessage}\n") }
            cleanup()
            return
        }

        openCamera(cameraId) {
            startEncoderLoop(rtspServer, aacAudioRecorder, bufferInfo)
        }
    }

    private fun findBestSize(cameraManager: CameraManager, cameraId: String): Size {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaCodec::class.java) ?: return Size(requestedWidth, requestedHeight)

        val target = requestedWidth.toLong() * requestedHeight
        return sizes.minByOrNull { size ->
            val pixels = size.width.toLong() * size.height
            kotlin.math.abs(pixels - target)
        } ?: Size(requestedWidth, requestedHeight)
    }

    private fun openCamera(cameraId: String, onReady: () -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            logView.post { logView.append("Camera permission not granted\n") }
            cleanup()
            return
        }

        logView.post { logView.append("Opening camera: $cameraId\n") }
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                mCameraDevice = camera
                logView.post { logView.append("Camera opened\n") }
                createCaptureSession(camera, onReady)
            }

            override fun onDisconnected(camera: CameraDevice) {
                logView.post { logView.append("Camera disconnected\n") }
                camera.close()
                cleanup()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                logView.post { logView.append("Camera error: $error\n") }
                camera.close()
                cleanup()
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
        val handler = mCameraHandler ?: return

        val outputConfig = OutputConfiguration(surface)
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(outputConfig),
            Executor { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    mCaptureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    session.setRepeatingRequest(request, null, handler)
                    logView.post { logView.append("Camera streaming started\n") }
                    onReady()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    logView.post { logView.append("Camera session config failed\n") }
                    cleanup()
                }
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun extractVideoInfo(encoder: MediaCodec, rtspServer: RtspServer) {
        if (mVideoInfoSet) return
        val format = encoder.outputFormat
        val sps = format.getByteBuffer("csd-0")
        val pps = format.getByteBuffer("csd-1")
        if (sps != null && pps != null) {
            sps.position(0)
            pps.position(0)
            rtspServer.setVideoInfo(sps, pps, null)
            mVideoInfoSet = true
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
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        extractVideoInfo(encoder, rtspServer)
                        continue
                    }
                    if (index < 0) continue
                    if (!mVideoInfoSet && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        extractVideoInfo(encoder, rtspServer)
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        encoder.releaseOutputBuffer(index, false)
                        continue
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
        mVideoInfoSet = false
        logView.post { logView.append("Camera recorder released\n") }
    }

    private fun cleanup() {
        mCameraThread?.quitSafely()
        mEncoderSurface?.release()
        try { mAvcEncoder?.release() } catch (_: Exception) {}
        mRunning = false
    }

    fun release() {
        mRunning = false
    }
}
