import { NitroModules } from 'react-native-nitro-modules'
import type { TencentCloudTts, TencentCloudTtsConfig } from './TencentCloudTts.nitro'

type TtsEventListener = {
  onError?: (error: string) => void
  onData?: (data: string) => void
  onMessage?: (message: string) => void
  onFinish?: () => void
}

class TtsEngine {
  private native: TencentCloudTts

  constructor() {
    this.native = NitroModules.createHybridObject<TencentCloudTts>('TencentCloudTts')
  }

  setup(config: TencentCloudTtsConfig) {
    this.native.setup(config)
  }

  synthesize(text: string) {
    this.native.synthesize(text)
  }

  cancel() {
    this.native.cancel()
  }

  stop() {
    this.native.stop()
  }

  setApiParam(key: string, value: string) {
    this.native.setApiParam(key, value)
  }

  setListener(listener: TtsEventListener) {
    this.native.setEventCallback((event, data, error) => {
      switch (event) {
        case 'onFinish':
          listener.onFinish?.()
          break
        case 'onError':
          listener.onError?.(error)
          break
        case 'onData':
          listener.onData?.(data)
          break
        case 'onMessage':
          listener.onMessage?.(data)
          break
        }
    })
  }
}

export type { TencentCloudTtsConfig, TtsEventListener }
export { TtsEngine }
