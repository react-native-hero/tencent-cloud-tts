package com.margelo.nitro.reactnativehero.tencentcloudtts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.facebook.proguard.annotations.DoNotStrip
import com.tencent.cloud.stream.tts.*
import com.tencent.cloud.stream.tts.core.ws.Credential
import com.tencent.cloud.stream.tts.core.ws.SpeechClient
import java.nio.ByteBuffer
import java.util.UUID

// MARK: - Streaming PCM Player

private class StreamingPcmPlayer(private val sampleRate: Int) {
  private var audioTrack: AudioTrack? = null
  private val bufferSize = maxOf(
    AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
    4096,
  )

  fun write(data: ByteArray) {
    if (audioTrack == null) {
      start()
    }
    audioTrack?.write(data, 0, data.size)
  }

  private fun start() {
    stop()
    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(bufferSize)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
      .apply { play() }
  }

  fun stop() {
    try {
      audioTrack?.stop()
    } catch (_: Exception) {}
    try {
      audioTrack?.release()
    } catch (_: Exception) {}
    audioTrack = null
  }
}

// MARK: - TencentCloudTts

@DoNotStrip
class TencentCloudTts : HybridTencentCloudTtsSpec() {
  private var request: FlowingSpeechSynthesizerRequest? = null
  private var credential: Credential? = null
  private var savedConfig: TencentCloudTtsConfig? = null
  private var synthesizer: FlowingSpeechSynthesizer? = null
  private var eventCallback: ((event: String, data: String, error: String) -> Unit)? = null
  private var streamingPlayer: StreamingPcmPlayer? = null
  private var configuredSampleRate: Int = 16000
  private var pendingText: String? = null
  private var buildCount = 0L

  override fun setup(config: TencentCloudTtsConfig) {
    configuredSampleRate = config.sampleRate?.toInt() ?: 16000
    savedConfig = config

    credential = Credential(
      config.appId,
      config.secretId,
      config.secretKey,
      config.token ?: "",
    )

    request = createRequest(config)
    // Save credentials only; connect on first synthesize (avoid double buildSynthesizer).
    synthesizer = null
  }

  private fun createRequest(config: TencentCloudTtsConfig): FlowingSpeechSynthesizerRequest {
    val req = FlowingSpeechSynthesizerRequest()
    config.volume?.let { req.volume = it.toFloat() }
    config.speed?.let { req.speed = it.toFloat() }
    config.codec?.let { req.codec = it }
    config.voiceType?.let { req.voiceType = it.toInt() }
    config.sampleRate?.let { req.sampleRate = it.toInt() }
    req.sessionId = UUID.randomUUID().toString()

    if (config.connectTimeout != null && config.connectTimeout!! > 0) {
      req.set("connectTimeout", config.connectTimeout!!.toInt().toString())
    }
    return req
  }

  private fun resolveStartTimeoutMs(): Long {
    val fromConfig = savedConfig?.connectTimeout?.toLong() ?: 0L
    return when {
      fromConfig in 5000L..60000L -> fromConfig
      else -> 15000L
    }
  }

  override fun synthesize(text: String) {
    stopAudio()

    if (synthesizer != null) {
      pendingText = text
      try {
        synthesizer?.cancel()
      } catch (_: Exception) {}
      // Rebuild via onSynthesisCancel with pendingText; no 3s retry timer.
    } else {
      pendingText = text
      buildSynthesizer()
    }
  }

  override fun cancel() {
    pendingText = null
    stopAudio()
    try {
      synthesizer?.cancel()
    } catch (_: Exception) {}
    synthesizer = null
  }

  override fun stop() {
    pendingText = null
    stopAudio()
    try {
      synthesizer?.stop()
    } catch (_: Exception) {}
  }

  override fun setEventCallback(callback: (event: String, data: String, error: String) -> Unit) {
    eventCallback = callback
  }

  override fun setApiParam(key: String, value: String) {
    request?.set(key, value)
  }

  // MARK: - Private

  private fun failSynthesis(message: String) {
    pendingText = null
    synthesizer = null
    stopAudio()
    sendEvent("onError", "", message)
  }

  private fun buildSynthesizer() {
    buildCount++
    val gen = buildCount

    synthesizer = null

    val cred = credential ?: run {
      failSynthesis("TTS credential not configured")
      return
    }
    val config = savedConfig ?: run {
      failSynthesis("TTS config not configured")
      return
    }
    val req = createRequest(config)

    try {
      val startTimeoutMs = resolveStartTimeoutMs()
      val syn = FlowingSpeechSynthesizer(speechClient, cred, req, createListener(gen))
      syn.start(startTimeoutMs)
      synthesizer = syn
    } catch (e: Exception) {
      failSynthesis(e.message ?: "buildSynthesizer failed")
    }
  }

  private fun createListener(generation: Long): FlowingSpeechSynthesizerListener {
    return object : FlowingSpeechSynthesizerListener() {
      private fun isStale(): Boolean {
        return generation != buildCount
      }

      override fun onSynthesisStart(response: SpeechSynthesizerResponse) {
        if (isStale()) {
          return
        }

        pendingText?.let { text ->
          pendingText = null
          Handler(Looper.getMainLooper()).post {
            try {
              synthesizer?.process(text)
              synthesizer?.stop()
            } catch (e: Exception) {
              failSynthesis(e.message ?: "synthesis process failed")
            }
          }
        }

        // onReady event is no longer sent; connection is managed internally
      }

      override fun onSynthesisEnd(response: SpeechSynthesizerResponse) {
        if (isStale()) {
          return
        }
        synthesizer = null

        sendEvent("onFinish", "", "")
      }

      override fun onAudioResult(buffer: ByteBuffer) {
        if (isStale()) return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        if (streamingPlayer == null) {
          streamingPlayer = StreamingPcmPlayer(configuredSampleRate)
        }
        streamingPlayer?.write(bytes)

        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        sendEvent("onData", base64, "")
      }

      override fun onTextResult(response: SpeechSynthesizerResponse) {
        if (isStale()) return
        response.result?.subtitles?.forEach { subtitle ->
          sendEvent("onMessage", subtitle.text, "")
        }
      }

      override fun onSynthesisCancel() {
        if (isStale()) {
          return
        }
        synthesizer = null
        stopAudio()

        pendingText?.let {
          buildSynthesizer()
        }
      }

      override fun onSynthesisFail(response: SpeechSynthesizerResponse) {
        if (isStale()) {
          return
        }
        failSynthesis(response.message ?: "合成失败")
      }
    }
  }

  private fun stopAudio() {
    streamingPlayer?.stop()
    streamingPlayer = null
  }

  private fun sendEvent(event: String, data: String, error: String) {
    eventCallback?.invoke(event, data, error)
  }

  companion object {
    /** Official doc: SpeechClient should be app-wide singleton. */
    private val speechClient: SpeechClient = SpeechClient()
  }
}
