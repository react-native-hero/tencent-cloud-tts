import AVFoundation
import QCloudStreamTTS
import VoiceCommon

// MARK: - Streaming PCM Player
/// 使用 AVAudioEngine 实现实时 PCM 音频流播报
/// 注意: AVAudioEngine/AVAudioPlayerNode 非线程安全，
/// 所有操作必须派发到主线程执行。
/// mainMixerNode 只接受 Float32 格式，所以使用 .pcmFormatFloat32
/// 在 enqueue 时将 Int16 原始 PCM 转换为 Float32。
private class StreamingPCMPlayer {
  private let engine = AVAudioEngine()
  private let playerNode = AVAudioPlayerNode()
  private let audioFormat: AVAudioFormat
  private var engineStarted = false
  private var pendingBufferCount: Int = 0
  var onAllBuffersPlayed: (() -> Void)?

  init?(sampleRate: Int) {
    // mainMixerNode 只支持 Float32，必须使用 pcmFormatFloat32
    guard let format = AVAudioFormat(
      commonFormat: .pcmFormatFloat32,
      sampleRate: Double(sampleRate),
      channels: 1,
      interleaved: false
    ) else { return nil }
    self.audioFormat = format

    engine.attach(playerNode)
    engine.connect(playerNode, to: engine.mainMixerNode, format: audioFormat)
    // 延迟到首次 enqueue 时在主线程启动 engine
  }

  func enqueue(pcmData: Data) {
    let frameLength = pcmData.count / MemoryLayout<Int16>.size
    guard frameLength > 0 else { return }
    guard let buffer = AVAudioPCMBuffer(
      pcmFormat: audioFormat,
      frameCapacity: AVAudioFrameCount(frameLength)
    ) else { return }
    buffer.frameLength = AVAudioFrameCount(frameLength)

    // 将 Int16 PCM 转换为 Float32（mainMixerNode 需要）
    pcmData.withUnsafeBytes { ptr in
      guard let samples = ptr.baseAddress?.assumingMemoryBound(to: Int16.self) else { return }
      guard let floatData = buffer.floatChannelData?.pointee else { return }
      let scale = Float32(Int16.max)
      for i in 0..<frameLength {
        floatData[i] = Float32(samples[i]) / scale
      }
    }

    // AVAudioEngine 操作必须在主线程执行
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      if !self.engineStarted {
        do {
          try self.engine.start()
          self.engineStarted = true
        } catch {
          return
        }
      }
      self.pendingBufferCount += 1
      self.playerNode.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
        DispatchQueue.main.async {
          guard let self = self else { return }
          self.pendingBufferCount -= 1
          if self.pendingBufferCount <= 0 {
            self.onAllBuffersPlayed?()
          }
        }
      }
      if !self.playerNode.isPlaying {
        self.playerNode.play()
      }
    }
  }

  func flush() {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.playerNode.stop()
    }
  }

  func hasPendingBuffers() -> Bool {
    return pendingBufferCount > 0
  }

  func stop() {
    if Thread.isMainThread {
      playerNode.stop()
      engine.stop()
      engineStarted = false
    } else {
      DispatchQueue.main.sync { [weak self] in
        guard let self = self else { return }
        self.playerNode.stop()
        self.engine.stop()
        self.engineStarted = false
      }
    }
  }

  deinit {
    if engine.isRunning {
      engine.stop()
    }
  }
}

// MARK: - TTS Listener

private class TTSListener: NSObject, QCloudStreamTTSListener {
  private weak var tts: TencentCloudTts?
  var collectedAudio = Data()

  init(tts: TencentCloudTts) {
    self.tts = tts
  }

  func onFinish() {
    tts?.onControllerFinish()
  }

  func onError(_ error: Error) {
    tts?.sendEvent("onError", data: "", error: error.localizedDescription)
  }

  func onLog(_ value: String, level: Int32) {
    tts?.sendEvent("onLog", data: value, error: "")
  }

  func onData(_ data: Data) {
    collectedAudio.append(data)
    let base64 = data.base64EncodedString()
    tts?.sendEvent("onData", data: base64, error: "")
    tts?.onAudioData(data)
  }

  func onMessage(_ msg: String) {
    tts?.sendEvent("onMessage", data: msg, error: "")
  }

  func onReady() {
    tts?.onControllerReady()
  }

  func reset() {
    collectedAudio = Data()
  }
}

// MARK: - TencentCloudTts

class TencentCloudTts: HybridTencentCloudTtsSpec {
  private var qcloudConfig: QCloudStreamTTSConfig?
  private var controller: QCloudStreamTTSController?
  private lazy var listener = TTSListener(tts: self)
  private var eventCallback: ((String, String, String) -> Void)?
  private var configuredSampleRate: Int = 16000

  private var pendingText: String?
  private var pendingSynthesizeText: String?

  private var streamingPlayer: StreamingPCMPlayer?
  /// 如果音频是非 PCM 格式（WAV/MP3），回退为收集完毕后一次性播放
  private var useFallbackPlayback = false
  /// 防止音频会话被重复配置
  private var audioSessionConfigured = false

  // MARK: Prebuffer（逐句播报无间隔衔接）
  /// drain 期间并行合成下一句，音频暂存到此 buffer
  private var isPrebuffering = false
  private var prebufferChunks: [Data] = []
  private var prebufferComplete = false
  private var prebufferIsFallback = false

  // MARK: - HybridTencentCloudTtsSpec

  func setup(config: TencentCloudTtsConfig) throws {
    configuredSampleRate = Int(config.sampleRate ?? 16000)

    let c = QCloudStreamTTSConfig()

    c.appID = config.appId
    c.secretID = config.secretId
    c.secretKey = config.secretKey

    if let token = config.token, !token.isEmpty {
      c.token = token
    }
    if let timeout = config.connectTimeout, timeout > 0 {
      c.connectTimeout = Int32(timeout)
    }

    if let value = config.voiceType {
      c.setApiParam("VoiceType", ivalue: Int(value))
    }
    if let value = config.codec {
      c.setApiParam("Codec", value: value)
    }
    if let value = config.speed {
      c.setApiParam("Speed", fvalue: Float(value))
    }
    if let value = config.volume {
      c.setApiParam("Volume", fvalue: Float(value))
    }
    if let value = config.sampleRate {
      c.setApiParam("SampleRate", ivalue: Int(value))
    }

    self.qcloudConfig = c
    // 延迟到首次 synthesize 再建连接
  }

  func synthesize(text: String) throws {
    if streamingPlayer?.hasPendingBuffers() == true {
      startPrebuffer(text)
      return
    }
    pendingSynthesizeText = nil
    performSynthesize(text)
  }

  private func startPrebuffer(_ text: String) {
    isPrebuffering = true
    prebufferChunks = []
    prebufferComplete = false
    prebufferIsFallback = false

    pendingText = text
    buildController()
  }

  private func performSynthesize(_ text: String) {
    stopAudio()
    useFallbackPlayback = false
    audioSessionConfigured = false

    controller?.cancel()
    controller = nil

    pendingText = text
    buildController()
  }

  func cancel() throws {
    pendingText = nil
    pendingSynthesizeText = nil
    clearPrebuffer()
    stopAudio()
    controller?.cancel()
    controller = nil
  }

  func stop() throws {
    pendingText = nil
    pendingSynthesizeText = nil
    clearPrebuffer()
    stopAudio()
    controller?.stop()
    controller = nil
  }

  private func clearPrebuffer() {
    isPrebuffering = false
    prebufferChunks = []
    prebufferComplete = false
    prebufferIsFallback = false
  }

  func setEventCallback(callback: @escaping (String, String, String) -> Void) throws {
    eventCallback = callback
  }

  func setApiParam(key: String, value: String) throws {
    qcloudConfig?.setApiParam(key, value: value)
  }

  // MARK: - Controller Lifecycle

  private func buildController() {
    controller?.cancel()
    controller = nil
    useFallbackPlayback = false

    guard let config = qcloudConfig else { return }
    controller = config.build(listener)
  }

  fileprivate func onControllerReady() {
    if let text = pendingText {
      pendingText = nil
      controller?.synthesis(text)
      controller?.stop()
    }
  }

  fileprivate func onControllerFinish() {
    controller = nil

    if isPrebuffering {
      prebufferComplete = true
      DispatchQueue.main.async { [weak self] in
        self?.flushBufferedNextIfReady()
      }
      return
    }

    // 非 PCM 格式（WAV/MP3），此时音频已全部收集，一次性播放
    if useFallbackPlayback {
      playCollectedAudio()
    }

    sendEvent("onFinish", data: "", error: "")
  }

  // MARK: - Audio Streaming

  private func configureAudioSession() {
    guard !audioSessionConfigured else { return }
    audioSessionConfigured = true
    let session = AVAudioSession.sharedInstance()
    do {
      try session.setCategory(.playback, mode: .default)
      try session.setActive(true)
    } catch {
      audioSessionConfigured = false
      sendEvent("onError", data: "", error: "音频会话配置失败: \(error.localizedDescription)")
    }
  }

  fileprivate func onAudioData(_ data: Data) {
    if isPrebuffering {
      if prebufferChunks.isEmpty && !prebufferIsFallback {
        if isSelfDescribingAudio(data) {
          prebufferIsFallback = true
          return
        }
      }
      if !prebufferIsFallback {
        prebufferChunks.append(data)
      }
      return
    }

    // 首个数据包判断音频格式
    if streamingPlayer == nil, !useFallbackPlayback {
      if isSelfDescribingAudio(data) {
        useFallbackPlayback = true
        return
      }
      configureAudioSession()
      streamingPlayer = StreamingPCMPlayer(sampleRate: configuredSampleRate)
      streamingPlayer?.onAllBuffersPlayed = { [weak self] in
        guard let self = self else { return }
        self.onPlayerDrained()
      }
    }

    if useFallbackPlayback { return }

    streamingPlayer?.enqueue(pcmData: data)
  }

  private func stopAudio() {
    streamingPlayer?.onAllBuffersPlayed = nil
    streamingPlayer?.stop()
    streamingPlayer = nil
  }

  /// 当前 player buffer 全部播完时的回调
  private func onPlayerDrained() {
    streamingPlayer?.stop()
    streamingPlayer = nil

    if isPrebuffering && prebufferComplete {
      flushBufferedNext()
      return
    }

    if let pending = pendingSynthesizeText {
      pendingSynthesizeText = nil
      performSynthesize(pending)
    }
  }

  /// 如果 prebuffer 已就绪且旧 player 已 drain，则立即用缓冲数据起新 player 播放
  private func flushBufferedNextIfReady() {
    guard isPrebuffering, prebufferComplete else { return }
    guard streamingPlayer == nil || streamingPlayer?.hasPendingBuffers() == false else { return }
    flushBufferedNext()
  }

  private func flushBufferedNext() {
    guard !prebufferChunks.isEmpty else {
      isPrebuffering = false
      DispatchQueue.main.async { [weak self] in
        self?.sendEvent("onFinish", data: "", error: "")
      }
      return
    }

    isPrebuffering = false

    configureAudioSession()
    guard let player = StreamingPCMPlayer(sampleRate: configuredSampleRate) else {
      DispatchQueue.main.async { [weak self] in
        self?.sendEvent("onError", data: "", error: "创建播放器失败")
      }
      return
    }
    streamingPlayer = player
    streamingPlayer?.onAllBuffersPlayed = { [weak self] in
      guard let self = self else { return }
      self.onPlayerDrained()
    }

    for chunk in prebufferChunks {
      player.enqueue(pcmData: chunk)
    }
    prebufferChunks = []

    DispatchQueue.main.async { [weak self] in
      self?.sendEvent("onFinish", data: "", error: "")
    }
  }

  // MARK: - Fallback Playback

  private func playCollectedAudio() {
    let data = listener.collectedAudio
    guard !data.isEmpty else { return }

    configureAudioSession()

    let audioData: Data
    if isSelfDescribingAudio(data) {
      audioData = data
    } else {
      audioData = addWavHeader(to: data, sampleRate: configuredSampleRate)
    }

    do {
      let player = try AVAudioPlayer(data: audioData)
      player.prepareToPlay()
      player.play()
    } catch {
      sendEvent("onError", data: "", error: "音频播放失败: \(error.localizedDescription)")
    }
  }

  // MARK: - Internal

  func sendEvent(_ event: String, data: String, error: String) {
    eventCallback?(event, data, error)
  }
}

// MARK: - WAV / Format Helpers

private func isSelfDescribingAudio(_ data: Data) -> Bool {
  if data.count >= 4,
     String(data: data[0..<4], encoding: .ascii) == "RIFF" {
    return true
  }
  if data.count >= 2,
     data[0] == 0xFF && (data[1] & 0xF0) == 0xF0 {
    return true
  }
  if data.count >= 3,
     String(data: data[0..<3], encoding: .ascii) == "ID3" {
    return true
  }
  return false
}

private func addWavHeader(to pcmData: Data, sampleRate: Int) -> Data {
  let channels: Int16 = 1
  let bitsPerSample: Int16 = 16
  let byteRate = Int32(sampleRate) * Int32(channels) * Int32(bitsPerSample) / 8
  let blockAlign = channels * bitsPerSample / 8
  let dataSize = Int32(pcmData.count)
  let fileSize = dataSize + 36

  var wav = Data()

  wav.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
  wav.append(fileSize.littleEndian.data)
  wav.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"

  wav.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
  wav.append(Int32(16).littleEndian.data)
  wav.append(Int16(1).littleEndian.data)
  wav.append(channels.littleEndian.data)
  wav.append(Int32(sampleRate).littleEndian.data)
  wav.append(byteRate.littleEndian.data)
  wav.append(blockAlign.littleEndian.data)
  wav.append(bitsPerSample.littleEndian.data)

  wav.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
  wav.append(dataSize.littleEndian.data)
  wav.append(pcmData)

  return wav
}

extension FixedWidthInteger {
  var data: Data {
    withUnsafeBytes(of: self) { Data($0) }
  }
}
