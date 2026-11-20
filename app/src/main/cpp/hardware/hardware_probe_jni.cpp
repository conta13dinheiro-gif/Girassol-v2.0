#include "hardware/soc_hardware_probe.hpp"
#include <jni.h>
#include <string>
extern "C" JNIEXPORT jstring JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_nativeHardwareProbe(JNIEnv* env, jobject) {
    const std::string report=girassol::hw::detectNativeHardware().toKeyValueText();
    return env->NewStringUTF(report.c_str());
}
