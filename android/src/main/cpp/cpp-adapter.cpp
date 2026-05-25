#include <jni.h>
#include "reactnativehero_tencentcloudttsOnLoad.hpp"

#include <fbjni/fbjni.h>


JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::reactnativehero_tencentcloudtts::registerAllNatives();
  });
}