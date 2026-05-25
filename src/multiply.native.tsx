import { NitroModules } from 'react-native-nitro-modules';
import type { TencentCloudTts } from './TencentCloudTts.nitro';

const TencentCloudTtsHybridObject =
  NitroModules.createHybridObject<TencentCloudTts>('TencentCloudTts');

export function multiply(a: number, b: number): number {
  return TencentCloudTtsHybridObject.multiply(a, b);
}
