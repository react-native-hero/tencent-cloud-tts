# @react-native-hero/tencent-cloud-tts

tencent-cloud-tts

## Installation


```sh
npm install @react-native-hero/tencent-cloud-tts react-native-nitro-modules

> `react-native-nitro-modules` is required as this library relies on [Nitro Modules](https://nitro.margelo.com/).
```


## Usage


```js
import {
  TtsEngine,
} from '@react-native-hero/tencent-cloud-tts'

const tts = new TtsEngine()

tts.setListener({
  onData: (data) => {
    console.log(`收到音频数据: ${data.length} 字节`)
  },
  onFinish: () => {
    console.log('合成完成')
  },
  onMessage: (msg) => {
    console.log(`消息: ${msg}`)
  },
  onError: (error) => {
    console.log(`错误: ${error}`)
  },
})
tts.setup({
  appId: 'appId',
  secretId: 'secretId',
  secretKey: 'secretKey',
  token: '',           // 临时密钥
  voiceType: 501004,   // 音色 ID 在这找 https://cloud.tencent.com/document/product/1073/92668
  volume: 0,           // 音量 [-10，10]，默认 0
  speed: 0,            // 语速 [-2，6]，默认 0
  codec: 'pcm',        // 编码格式 pcm/mp3，默认 pcm
  sampleRate: 16000,   // 采样率，默认 16000
})

// 合成语音 并实时播放
tts.synthesize('你好，欢迎使用腾讯云语音合成')

// 停止合成
tts.stop()

// 取消
tts.cancel()
```


## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
