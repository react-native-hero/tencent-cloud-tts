package com.margelo.nitro.reactnativehero.tencentcloudtts
  
import com.facebook.proguard.annotations.DoNotStrip

@DoNotStrip
class TencentCloudTts : HybridTencentCloudTtsSpec() {
  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }
}
