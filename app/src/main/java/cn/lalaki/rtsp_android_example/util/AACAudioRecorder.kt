package cn.lalaki.rtsp_android_example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioFormat.CHANNEL_IN_MONO
import android.media.AudioFormat.ENCODING_PCM_16BIT
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import com.pedro.rtspserver.RtspServer
import java.io.IOException
import java.nio.ByteBuffer

class AACAudioRecorder(
    context: Context,
    var mBufferInfo: MediaCodec.BufferInfo
) {
    private var minBufferSize = 0
    private var mAudioRecord: AudioRecord? = null
    private var mAacEncoder: MediaCodec? = null

    companion object {
        const val SAMPLE_RATE = 32000
    }

    init {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val encoding = ENCODING_PCM_16BIT
            val channelConfig = CHANNEL_IN_MONO
            minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
            val audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(encoding)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize)
                .build()
            mAudioRecord = audioRecord
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
            )
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, SAMPLE_RATE)
            audioFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            try {
                val aacEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                mAacEncoder = aacEncoder
                aacEncoder.configure(audioFormat, null, MediaCodec.CONFIGURE_FLAG_ENCODE, null)
                aacEncoder.start()
                audioRecord.startRecording()
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    fun sendAacBuffer(rtspServer: RtspServer, timestamp: Long) {
        val audioRecord = mAudioRecord ?: return
        val audioRawByteBuffer = ByteBuffer.allocateDirect(minBufferSize)
        val len = audioRecord.read(audioRawByteBuffer, minBufferSize, AudioRecord.READ_NON_BLOCKING)
        val aacEncoder = mAacEncoder
        if (len > 0 && aacEncoder != null) {
            val audioInputBufferIndex = aacEncoder.dequeueInputBuffer(0)
            if (audioInputBufferIndex > -1) {
                val encoderBuffer = aacEncoder.getInputBuffer(audioInputBufferIndex)
                if (encoderBuffer != null) {
                    val buffer = encoderBuffer.clear()
                    if (buffer is ByteBuffer) {
                        buffer.put(audioRawByteBuffer)
                    }
                }
                aacEncoder.queueInputBuffer(audioInputBufferIndex, 0, len, timestamp, 0)
                var audioOutputBufferIndex: Int
                while (true) {
                    audioOutputBufferIndex = aacEncoder.dequeueOutputBuffer(mBufferInfo, 0)
                    if (audioOutputBufferIndex < 0) break
                    val audioBuffer = aacEncoder.getOutputBuffer(audioOutputBufferIndex)
                    if (audioBuffer != null) {
                        rtspServer.sendAudio(audioBuffer, mBufferInfo)
                    }
                    aacEncoder.releaseOutputBuffer(audioOutputBufferIndex, false)
                }
            }
        }
    }

    fun release() {
        try { mAudioRecord?.stop() } catch (_: IllegalStateException) {}
        mAudioRecord?.release()
        try { mAacEncoder?.stop() } catch (_: IllegalStateException) {}
        try { mAacEncoder?.reset() } catch (_: IllegalStateException) {}
        mAacEncoder?.release()
    }
}
