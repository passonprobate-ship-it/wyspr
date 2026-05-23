package com.wyspr.feature.messaging.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimeMs: Long = 0L

    val isRecording: Boolean get() = recorder != null
    val elapsedMs: Long get() = if (isRecording) System.currentTimeMillis() - startTimeMs else 0L

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        outputFile = file
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(32_000)
            mr.setAudioSamplingRate(22_050)
            mr.setAudioChannels(1)
            mr.setMaxFileSize(MAX_FILE_BYTES)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            startTimeMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            mr.release()
            file.delete()
            outputFile = null
            false
        }
    }

    data class Result(val file: File, val durationMs: Long)

    fun stop(): Result? {
        val mr = recorder ?: return null
        val duration = System.currentTimeMillis() - startTimeMs
        return try {
            mr.stop()
            mr.release()
            recorder = null
            val f = outputFile ?: return null
            if (!f.exists() || f.length() == 0L) {
                f.delete()
                return null
            }
            Result(file = f, durationMs = duration)
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            mr.release()
            recorder = null
            outputFile?.delete()
            null
        }
    }

    fun cancel() {
        val mr = recorder ?: return
        try {
            mr.stop()
        } catch (_: Exception) {}
        mr.release()
        recorder = null
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        private const val TAG = "AudioRecorder"
        const val MAX_FILE_BYTES = 140_000L
        const val MAX_DURATION_MS = 60_000L
    }
}
