package cn.lalaki.rtsp_android_example.util

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.view.Surface
import android.view.View
import android.widget.TextView
import cn.lalaki.rtsp_android_example.IDispose
import cn.lalaki.rtsp_android_example.MainApp
import com.pedro.rtspserver.RtspServer
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

open class AVCVideoRecorder(
    mMediaProjection: MediaProjection,
    var logView: TextView,
    width: Int,
    height: Int
) {
    private var mVirtualDisplay: VirtualDisplay? = null
    private var mSurface: Surface? = null
    private var mAvcEncoder: MediaCodec? = null
    var mRunning = false

    init {
        logView.append(String.format("Pixel: w:%s, h:%s\n", width, height))
        val videoFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        videoFormat.setInteger(
            MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
        try {
            val avcEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mAvcEncoder = avcEncoder
            avcEncoder.configure(videoFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE, null)
            val surface = avcEncoder.createInputSurface()
            mSurface = surface
            avcEncoder.start()
            mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "vd-9",
                width,
                height,
                1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                surface,
                null,
                null
            )
        } catch (e: IllegalArgumentException) {
            logView.append(e.localizedMessage)
        } catch (e: IllegalStateException) {
            logView.append(e.localizedMessage)
        } catch (e: IOException) {
            logView.append(e.localizedMessage)
        } catch (e: SecurityException) {
            logView.append(e.localizedMessage)
        }
        val virtualDisplay = mVirtualDisplay
        if (virtualDisplay == null) {
            logView.append("此分辨率可能不兼容，请尝试不同的分辨率！！！")
        }
    }

    private fun csd0Handler(data: ByteArray, rtspServer: RtspServer) {
        // H.264 csd-0 contains SPS + PPS (no VPS unlike H.265)
        var spsPosition = -1
        var ppsPosition = -1
        var i = 0
        while (i < data.size - 3) {
            // Look for start codes (0x00 0x00 0x00 0x01)
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
        }
    }

    open fun start(
        rtspServer: RtspServer,
        aacAudioRecorder: AACAudioRecorder?,
        bufferInfo: MediaCodec.BufferInfo,
        floatView: View?
    ) {
        thread {
            mRunning = true
            try {
                val avcEncoder = mAvcEncoder
                if (avcEncoder != null) {
                    val beginTime = System.nanoTime()
                    while (mRunning) {
                        val timestamp = (System.nanoTime() - beginTime) / 1000L
                        aacAudioRecorder?.sendAacBuffer(rtspServer, timestamp)
                        val avcOutputBufferIndex = avcEncoder.dequeueOutputBuffer(bufferInfo, 0)
                        if (avcOutputBufferIndex < 0) {
                            floatView?.postInvalidate()
                            continue
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                            val formatBuffer =
                                avcEncoder.getOutputFormat(avcOutputBufferIndex).getByteBuffer(
                                    "csd-0"
                                )
                            if (formatBuffer != null) {
                                csd0Handler(formatBuffer.array(), rtspServer)
                            }
                        }
                        val outputBuffer = avcEncoder.getOutputBuffer(avcOutputBufferIndex)
                        if (outputBuffer != null) {
                            bufferInfo.presentationTimeUs = timestamp
                            rtspServer.sendVideo(outputBuffer, bufferInfo)
                        }
                        avcEncoder.releaseOutputBuffer(avcOutputBufferIndex, false)
                    }
                }
            } catch (e: IllegalStateException) {
                logView.post {
                    logView.append(e.localizedMessage)
                }
            }
            stop()
        }
    }

    private fun stop() {
        val avcEncoder = mAvcEncoder
        if (avcEncoder != null) {
            avcEncoder.stop()
            avcEncoder.reset()
            avcEncoder.release()
        }
        mSurface?.release()
        mVirtualDisplay?.release()
        logView.post {
            logView.append("All objects are released.\n")
        }
        mRunning = false
        val appContext = logView.context.applicationContext
        if (appContext is IDispose) {
            appContext.onRelease()
        }
    }

    open fun release() {
        mRunning = false
    }
}
