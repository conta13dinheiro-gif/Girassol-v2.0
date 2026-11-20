#include "synth.hpp"
#include <aaudio/AAudio.h>
#include <algorithm>
#include <cmath>
#include <jni.h>
#include <atomic>
#include <mutex>

namespace {
using girassol::audio::Synth;
using girassol::audio::Parameters;
static_assert(std::atomic<float>::is_always_lock_free);
static_assert(std::atomic<int>::is_always_lock_free);
class Audio {
public:
    std::mutex control; // Control thread only. The callback never acquires it.
    AAudioStream* stream=nullptr;
    Synth synth;
    std::atomic<float> volume{0},left{0},right{0},mastery{0},social{0},weather{0},tension{0},milestone{0},pitch{1},synchrony{0};
    std::array<std::atomic<float>,8> ratios{{1.0f,1.067f,1.125f,1.2f,1.333f,1.5f,1.6f,1.8f}};
    std::atomic<bool> speech{false};
    std::atomic<int> error{0};
    int sample_rate=48000;
    void stop() {
        if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); stream=nullptr; }
    }
    ~Audio() { stop(); }
    static aaudio_data_callback_result_t data(AAudioStream*,void* user,void* output,int32_t frames) {
        auto& a=*static_cast<Audio*>(user);
        Parameters p;p.speech=a.speech.load(std::memory_order_relaxed);
        p.volume=a.volume.load(std::memory_order_relaxed); p.left_steps=a.left.load(std::memory_order_relaxed);
        p.right_steps=a.right.load(std::memory_order_relaxed); p.mastery=a.mastery.load(std::memory_order_relaxed);
        p.social=a.social.load(std::memory_order_relaxed); p.weather=a.weather.load(std::memory_order_relaxed);
        p.tension=a.tension.load(std::memory_order_relaxed); p.milestone=a.milestone.load(std::memory_order_relaxed);
        p.pitch=a.pitch.load(std::memory_order_relaxed); p.synchrony=a.synchrony.load(std::memory_order_relaxed);
        for(std::size_t i=0;i<p.ratios.size();++i) p.ratios[i]=a.ratios[i].load(std::memory_order_relaxed);
        return a.synth.render(static_cast<float*>(output),frames,a.sample_rate,p) ?
            AAUDIO_CALLBACK_RESULT_CONTINUE : AAUDIO_CALLBACK_RESULT_STOP;
    }
    static void failed(AAudioStream*,void* user,aaudio_result_t code) {
        // Closing in this callback can deadlock. The next control-thread update handles recovery.
        static_cast<Audio*>(user)->error.store(code,std::memory_order_relaxed);
    }
} audio;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_audio_NativeAudio_start(JNIEnv*,jobject,jlong seed) {
    std::lock_guard guard(audio.control);
    audio.stop(); audio.error.store(0);
    audio.synth=Synth(static_cast<std::uint32_t>(seed)^static_cast<std::uint32_t>(static_cast<std::uint64_t>(seed)>>32));
    AAudioStreamBuilder* builder=nullptr;
    auto result=AAudio_createStreamBuilder(&builder);
    if (result!=AAUDIO_OK) return result;
    AAudioStreamBuilder_setDirection(builder,AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder,AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(builder,AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder,2);
    AAudioStreamBuilder_setUsage(builder,AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(builder,AAUDIO_CONTENT_TYPE_SONIFICATION);
    AAudioStreamBuilder_setPerformanceMode(builder,AAUDIO_PERFORMANCE_MODE_POWER_SAVING);
    AAudioStreamBuilder_setDataCallback(builder,Audio::data,&audio);
    AAudioStreamBuilder_setErrorCallback(builder,Audio::failed,&audio);
    result=AAudioStreamBuilder_openStream(builder,&audio.stream);
    AAudioStreamBuilder_delete(builder);
    if (result!=AAUDIO_OK) { audio.stop(); return result; }
    audio.sample_rate=AAudioStream_getSampleRate(audio.stream);
    if (AAudioStream_getFormat(audio.stream)!=AAUDIO_FORMAT_PCM_FLOAT || AAudioStream_getChannelCount(audio.stream)!=2 ||
        audio.sample_rate<8000 || audio.sample_rate>192000) { audio.stop(); return AAUDIO_ERROR_INVALID_FORMAT; }
    result=AAudioStream_requestStart(audio.stream);
    if (result!=AAUDIO_OK) audio.stop();
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_audio_NativeAudio_stop(JNIEnv*,jobject) {
    std::lock_guard guard(audio.control); audio.stop();
}
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_audio_NativeAudio_nativeUpdate(JNIEnv* env,jobject,jfloat volume,jfloat left,jfloat right,jfloat mastery,jfloat social,jfloat weather,jfloat tension,jfloat milestone,jfloat pitch,jfloatArray ratios,jfloat synchrony) {
    if(!ratios || env->GetArrayLength(ratios)!=8) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    jfloat values[8]{};
    env->GetFloatArrayRegion(ratios,0,8,values);
    if(env->ExceptionCheck()) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    std::lock_guard guard(audio.control);
    audio.volume.store(volume,std::memory_order_relaxed); audio.left.store(left,std::memory_order_relaxed);
    audio.right.store(right,std::memory_order_relaxed); audio.mastery.store(mastery,std::memory_order_relaxed);
    audio.social.store(social,std::memory_order_relaxed);
    audio.weather.store(weather,std::memory_order_relaxed); audio.tension.store(tension,std::memory_order_relaxed); audio.milestone.store(milestone,std::memory_order_relaxed); audio.pitch.store(std::isfinite(pitch)?std::clamp(pitch,.5f,2.0f):1.0f,std::memory_order_relaxed); audio.synchrony.store(std::isfinite(synchrony)?std::clamp(synchrony,0.0f,1.0f):0.0f,std::memory_order_relaxed);
    for(std::size_t i=0;i<8;++i) audio.ratios[i].store(std::isfinite(values[i])?std::clamp(values[i],.25f,4.0f):1.0f,std::memory_order_relaxed);
    const int error=audio.error.load(std::memory_order_relaxed);
    return error!=AAUDIO_OK ? error : (audio.stream ? AAUDIO_OK : AAUDIO_ERROR_INVALID_STATE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_enhikyy_girassol_audio_NativeAudio_duck(JNIEnv*,jobject,jboolean speech) {
    audio.speech.store(speech==JNI_TRUE,std::memory_order_relaxed);
}
