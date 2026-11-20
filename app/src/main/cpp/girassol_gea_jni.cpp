#include <jni.h>

// This call cannot allocate or throw across the JNI boundary.
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_contractVersion(JNIEnv*, jobject) {
    return 1;
}
