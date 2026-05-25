import type { HybridObject } from 'react-native-nitro-modules';

export interface TencentCloudTtsConfig {
  appId: string;
  secretId: string;
  secretKey: string;
  token?: string;
  connectTimeout?: number;
  voiceType?: number;
  codec?: string;
  speed?: number;
  volume?: number;
  sampleRate?: number;
}

export interface TencentCloudTts extends HybridObject<{
  ios: 'swift';
  android: 'kotlin';
}> {
  /**
   * 初始化 TTS 配置
   */
  setup(config: TencentCloudTtsConfig): void;

  /**
   * 发送合成文本
   */
  synthesize(text: string): void;

  /**
   * 取消合成任务
   */
  cancel(): void;

  /**
   * 停止合成任务
   */
  stop(): void;

  /**
   * 注册事件回调
   * event: 'onReady' | 'onFinish' | 'onError' | 'onData' | 'onMessage'
   * data: 事件相关数据（base64 音频数据或消息）
   * error: 错误信息
   */
  setEventCallback(callback: (event: string, data: string, error: string) => void): void;

  /**
   * 设置 API 参数（用于设置 VoiceType、Codec 等腾讯云 API 参数）
   */
  setApiParam(key: string, value: string): void;
}
