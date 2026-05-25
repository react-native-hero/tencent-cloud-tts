import type { TencentCloudTtsConfig } from './TencentCloudTts.nitro';

type TtsEventListener = {
  onReady?: () => void;
  onError?: (error: string) => void;
  onData?: (data: string) => void;
  onMessage?: (message: string) => void;
  onFinish?: () => void;
};

class TtsEngine {
  setup(_config: TencentCloudTtsConfig) {
    throw new Error('TTS is not available on this platform');
  }

  synthesize(_text: string) {
    throw new Error('TTS is not available on this platform');
  }

  cancel() {
    throw new Error('TTS is not available on this platform');
  }

  stop() {
    throw new Error('TTS is not available on this platform');
  }

  setApiParam(_key: string, _value: string) {
    throw new Error('TTS is not available on this platform');
  }

  setListener(_listener: TtsEventListener) {
    throw new Error('TTS is not available on this platform');
  }
}

export type { TencentCloudTtsConfig, TtsEventListener };
export { TtsEngine };
export function createTts(): TtsEngine {
  return new TtsEngine();
}
