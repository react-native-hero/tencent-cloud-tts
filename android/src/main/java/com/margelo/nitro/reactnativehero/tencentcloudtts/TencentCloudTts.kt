package com.margelo.nitro.reactnativehero.tencentcloudtts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
  private var isReady = false
  private var pendingText: String? = null
  private var silentReconnect = false
  private var collectedAudio = ByteArray(0)
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
    buildSynthesizer()
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
    if (config.language != null) {
      req.set("Language", config.language!!.toInt().toString())
    }
    return req
  }

  override fun synthesize(text: String) {
    sendDebug("synthesize()  called, text=\"${text.take(20)}...\", isReady=$isReady, synthesizer=${synthesizer != null}, buildCount=$buildCount")

    collectedAudio = ByteArray(0)
    stopAudio()

    if (isReady && synthesizer != null) {
      sendDebug("→ 走直接路径: process+stop")
      // synthesizer 已就绪（onReady 首次合成），直接调用
      pendingText = null
      try {
        sendDebug("→ 执行 process()")
        synthesizer?.process(text)
        sendDebug("→ process() 完成，执行 stop()")
        synthesizer?.stop()
        sendDebug("→ stop() 完成")
      } catch (e: Exception) {
        sendDebug("→ process/stop 异常: ${e.message}")
      }
      isReady = false
      sendDebug("→ 直接路径结束，isReady=false")
    } else {
      sendDebug("→ 走重建路径: silentReconnect=true, 准备 buildSynthesizer()")
      // 未就绪（按钮点击等后续调用），重建连接走 pendingText
      // pendingText 必须在 buildSynthesizer 之前设置，因为 start() 会同步触发 onSynthesisStart
      silentReconnect = true
      pendingText = text
      buildSynthesizer()
      sendDebug("→ 重建路径结束")
    }
  }

  override fun cancel() {
    sendDebug("cancel() called")
    pendingText = null
    stopAudio()
    try { synthesizer?.cancel() } catch (_: Exception) {}
    isReady = false
    synthesizer = null
  }

  override fun stop() {
    sendDebug("stop() called, synthesizer=${synthesizer != null}")
    pendingText = null
    stopAudio()
    try { synthesizer?.stop() } catch (_: Exception) {}
    isReady = false
  }

  override fun setEventCallback(callback: (event: String, data: String, error: String) -> Unit) {
    eventCallback = callback
  }

  override fun setApiParam(key: String, value: String) {
    request?.set(key, value)
  }

  // MARK: - Private

  private fun buildSynthesizer() {
    // 先递增代数，使旧 listener 回调失效
    buildCount++
    val gen = buildCount
    sendDebug("buildSynthesizer() buildCount=$gen")

    try { synthesizer?.cancel() } catch (_: Exception) {}
    synthesizer = null
    isReady = false

    val cred = credential ?: run {
      sendDebug("buildSynthesizer: credential 为空，中止")
      return
    }

    // 每次重建刷新 sessionId，防止服务端因重复 sessionId 拒绝连接
    val req = createRequest(savedConfig ?: run {
      sendDebug("buildSynthesizer: savedConfig 为空，中止")
      return
    })

    try {
      // 每个 synthesizer 使用独立的 SpeechClient，避免共享 WebSocket 连接
      sendDebug("buildSynthesizer: 创建新 FlowingSpeechSynthesizer")
      val syn = FlowingSpeechSynthesizer(SpeechClient(), cred, req, createListener(gen))
      syn.start()
      synthesizer = syn
      sendDebug("buildSynthesizer: start() 完成")
    } catch (e: Exception) {
      sendDebug("buildSynthesizer 异常: ${e.message}")
    }
  }

  private fun createListener(generation: Long): FlowingSpeechSynthesizerListener {
    return object : FlowingSpeechSynthesizerListener() {
      private fun isStale(): Boolean {
        val stale = generation != buildCount
        if (stale) sendDebug("listener($generation) 已过期，buildCount=$buildCount，跳过")
        return stale
      }

      override fun onSynthesisStart(response: SpeechSynthesizerResponse) {
        if (isStale()) return
        sendDebug("onSynthesisStart 触发, generation=$generation, pendingText=${pendingText != null}, silentReconnect=$silentReconnect")

        isReady = true

        pendingText?.let { text ->
          pendingText = null
          sendDebug("→ 处理 pendingText: \"${text.take(20)}...\"")
          // 用 Handler.post 把 process+stop 放到主线程消息队列尾部，
          // 等 onSynthesisStart 返回后状态机从 STATE_START 过渡完成再执行
          Handler(Looper.getMainLooper()).post {
            try {
              sendDebug("→ 执行 process()")
              synthesizer?.process(text)
              sendDebug("→ process() 完成，执行 stop()")
              synthesizer?.stop()
              sendDebug("→ stop() 完成")
            } catch (e: Exception) {
              sendDebug("→ process/stop 异常: ${e.message}")
            }
          }
        }

        if (!silentReconnect) {
          sendDebug("→ 发送 onReady 事件")
          sendEvent("onReady", "", "")
        }
        silentReconnect = false
      }

      override fun onSynthesisEnd(response: SpeechSynthesizerResponse) {
        if (isStale()) return
        sendDebug("onSynthesisEnd 触发, generation=$generation")
        isReady = false
        synthesizer = null

        sendEvent("onFinish", "", "")
      }

      override fun onAudioResult(buffer: ByteBuffer) {
        if (isStale()) return
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        sendDebug("onAudioResult: ${bytes.size} 字节")

        collectedAudio = collectedAudio + bytes

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
        if (isStale()) return
        sendDebug("onSynthesisCancel 触发, generation=$generation")
        isReady = false
        synthesizer = null
        stopAudio()
      }

      override fun onSynthesisFail(response: SpeechSynthesizerResponse) {
        if (isStale()) return
        sendDebug("onSynthesisFail 触发, msg=${response.message}")
        isReady = false
        synthesizer = null
        stopAudio()
        sendEvent("onError", "", response.message ?: "合成失败")
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

  private fun sendDebug(msg: String) {
    sendEvent("onMessage", "[debug] $msg", "")
  }

  companion object {
  }
}
