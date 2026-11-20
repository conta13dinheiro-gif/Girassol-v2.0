#include <jni.h>

// This call cannot allocate or throw across the JNI boundary.
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_contractVersion(JNIEnv*, jobject) {
    return 5;
}

#include "cognitive/tsetlin_machine.hpp"
#include <new>
namespace {
void invalid(JNIEnv* env) {
    if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid Tsetlin state or input");
}
bool model(JNIEnv* env,jbyteArray input,girassol::TsetlinMachine& machine) {
    if(!input) { invalid(env);return false; }
    const auto size=env->GetArrayLength(input);
    if(size==0) return true;
    if(size!=static_cast<jsize>(machine.bytes)) { invalid(env);return false; }
    std::array<std::uint8_t,girassol::TsetlinMachine::bytes> data{};
    env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(data.data()));
    if(env->ExceptionCheck()) return false;
    if(!machine.load(data)) { invalid(env);return false; }
    return true;
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_tsetlinTrain(JNIEnv* env,jobject,jbyteArray input,jint features,jint label,jlong seed) {
    girassol::TsetlinMachine machine;
    if(features<0 || features>65535 || label<0 || label>1) { invalid(env);return nullptr; }
    if(!model(env,input,machine)) return nullptr;
    machine.train(features,label==1,static_cast<std::uint64_t>(seed));
    auto output=env->NewByteArray(machine.bytes);
    if(output) env->SetByteArrayRegion(output,0,machine.bytes,reinterpret_cast<const jbyte*>(machine.state.data()));
    return output;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_tsetlinVote(JNIEnv* env,jobject,jbyteArray input,jint features,jboolean early) {
    girassol::TsetlinMachine machine;
    if(features<0 || features>65535) { invalid(env);return 0; }
    if(!model(env,input,machine)) return 0;
    return machine.vote(features,early==JNI_TRUE);
}

#include "evolution/qd_wire.hpp"
namespace {
void invalid_qd(JNIEnv* env) {
    if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid bounded QD state or batch");
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_qdUpdate(JNIEnv* env,jobject,jobject output,jobject input,jint count,jlong tick,jboolean reset) {
    using namespace girassol::qd;
    if(!output || !input || count<1 || count>150 || tick<1 ||
       env->GetDirectBufferCapacity(output)!=static_cast<jlong>(wire_bytes) ||
       env->GetDirectBufferCapacity(input)!=static_cast<jlong>(candidate_bytes*count)) { invalid_qd(env);return JNI_FALSE; }
    auto* bytes=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(output));
    auto* batch=static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(input));
    if(!bytes || !batch) { invalid_qd(env);return JNI_FALSE; }
    const auto klass=env->GetObjectClass(output);
    if(!klass) return JNI_FALSE;
    const auto method=env->GetMethodID(klass,"isReadOnly","()Z");
    const bool read_only=method && env->CallBooleanMethod(output,method)==JNI_TRUE;
    env->DeleteLocalRef(klass);
    if(env->ExceptionCheck()) return JNI_FALSE;
    if(!method || read_only) { invalid_qd(env);return JNI_FALSE; }
    // No native heap allocation. All reads finish before the caller-owned output is modified.
    State state{};
    if(reset!=JNI_TRUE && !load({bytes,wire_bytes},state)) { invalid_qd(env);return JNI_FALSE; }
    std::array<Candidate,150> candidates{};
    for(int i=0;i<count;++i) {
        Reader reader{{batch+i*candidate_bytes,candidate_bytes}};
        candidates[i]=reader.candidate(true);
        for(std::size_t j=12;j<16;++j) if(batch[i*candidate_bytes+j]!=0) { invalid_qd(env);return JNI_FALSE; }
    }
    if(!state.update(candidates.data(),static_cast<std::size_t>(count),static_cast<std::uint64_t>(tick))) { invalid_qd(env);return JNI_FALSE; }
    return save(state,{bytes,wire_bytes}) ? JNI_TRUE : JNI_FALSE;
}

#include "audio/binaural_hrtf_spatializer.hpp"
#include <cstring>
namespace {
bool writable(JNIEnv* env,jobject buffer) {
    const auto klass=env->GetObjectClass(buffer);if(!klass) return false;
    const auto method=env->GetMethodID(klass,"isReadOnly","()Z");
    const bool ok=method && env->CallBooleanMethod(buffer,method)==JNI_FALSE;env->DeleteLocalRef(klass);
    return ok && !env->ExceptionCheck();
}
void invalid_audio(JNIEnv* env) {
    if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid bounded acoustic input");
}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_hrtfProcess(JNIEnv* env,jobject,jobject state,jobject mono,jobject stereo,jint frames,jint rate,jdouble azimuth,jdouble distance,jdouble occlusion,jboolean reset) {
    using namespace girassol::audio;
    if(!state || !mono || !stereo || frames<1 || frames>2048 || rate<8000 || rate>96000 || env->GetDirectBufferCapacity(state)!=544 || env->GetDirectBufferCapacity(mono)!=8192 || env->GetDirectBufferCapacity(stereo)!=16384 || !writable(env,state) || !writable(env,stereo)) { invalid_audio(env);return JNI_FALSE; }
    auto* source=env->GetDirectBufferAddress(mono);auto* target=env->GetDirectBufferAddress(stereo);auto* bytes=env->GetDirectBufferAddress(state);
    auto overlaps=[](void* a,std::size_t n,void* b,std::size_t m) { const auto x=reinterpret_cast<std::uintptr_t>(a),y=reinterpret_cast<std::uintptr_t>(b);return x<y+m && y<x+n; };
    if(!source || !target || !bytes || overlaps(bytes,544,source,8192) || overlaps(bytes,544,target,16384) || overlaps(source,8192,target,16384)) { invalid_audio(env);return JNI_FALSE; }
    HrtfState value{};
    if(reset==JNI_TRUE) value.rate=static_cast<std::uint32_t>(rate);
    else { std::memcpy(&value,bytes,sizeof(value));for(int i=536;i<544;++i) if(static_cast<unsigned char*>(bytes)[i]!=0) { invalid_audio(env);return JNI_FALSE; } }
    std::array<float,2048> input{};std::array<float,4096> output{};std::memcpy(input.data(),source,static_cast<std::size_t>(frames)*sizeof(float));
    if(!spatialize(value,input.data(),output.data(),frames,rate,azimuth,distance,occlusion)) { invalid_audio(env);return JNI_FALSE; }
    std::memcpy(target,output.data(),static_cast<std::size_t>(frames)*8);std::memcpy(bytes,&value,sizeof(value));std::memset(static_cast<unsigned char*>(bytes)+536,0,8);return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_acousticTrace(JNIEnv* env,jobject,jobject rooms,jint count,jlong seed,jobject endpoints,jobject result) {
    if(!rooms || !endpoints || !result || count<1 || count>192 || env->GetDirectBufferCapacity(rooms)!=static_cast<jlong>(count)*24 || env->GetDirectBufferCapacity(endpoints)!=56 || env->GetDirectBufferCapacity(result)!=24 || !writable(env,result)) { invalid_audio(env);return JNI_FALSE; }
    const auto* r=env->GetDirectBufferAddress(rooms);const auto* e=env->GetDirectBufferAddress(endpoints);auto* out=env->GetDirectBufferAddress(result);
    if(!r || !e || !out) { invalid_audio(env);return JNI_FALSE; }
    std::array<double,7> p{};std::memcpy(p.data(),e,56);
    if(std::any_of(p.begin(),p.end(),[](double v){return !std::isfinite(v) || std::abs(v)>2000;})) { invalid_audio(env);return JNI_FALSE; }
    try {
        girassol::Mesh mesh;if(!girassol::city_mesh(r,static_cast<std::size_t>(count)*24,static_cast<std::size_t>(count),static_cast<std::uint64_t>(seed),mesh)) { invalid_audio(env);return JNI_FALSE; }
        const double dx=p[0]-p[3],dy=p[1]-p[4],dz=p[2]-p[5];
        const double distance=std::sqrt(dx*dx+dy*dy+dz*dz);if(distance>2000) { invalid_audio(env);return JNI_FALSE; }
        const double azimuth=std::atan2(dx*std::cos(p[6])-dz*std::sin(p[6]),-dx*std::sin(p[6])-dz*std::cos(p[6]));
        const double output[3]{azimuth,distance,girassol::audio::acoustic_occlusion(mesh,{p[0],p[1],p[2]},{p[3],p[4],p[5]})};
        std::memcpy(out,output,sizeof(output));return JNI_TRUE;
    } catch(const std::bad_alloc&) {
        if(auto type=env->FindClass("java/lang/OutOfMemoryError")) env->ThrowNew(type,"Acoustic geometry memory budget exhausted");
        return JNI_FALSE;
    } catch(const std::exception& error) {
        if(auto type=env->FindClass("java/lang/IllegalStateException")) env->ThrowNew(type,error.what());
        return JNI_FALSE;
    }
}

#include "forge/lora_trainer_realtime.hpp"
extern "C" JNIEXPORT jboolean JNICALL
Java_com_enhikyy_girassol_cognition_GeaNativeBridge_loraStep(JNIEnv* env,jobject,jobject state,jobject input,jobject output,jboolean reset,jlong seed,jint action,jdouble reward) {
    using Model=girassol::forge::RealTimeLoraAdapter;
    // input: W0[64], x[8]. output: y[8]. action=-1 is inference only.
    auto reject=[&]() { if(!env->ExceptionCheck()) if(auto type=env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type,"Invalid bounded LoRA state or sample");return JNI_FALSE; };
    if(!state || !input || !output || env->GetDirectBufferCapacity(state)!=2592 || env->GetDirectBufferCapacity(input)!=576 || env->GetDirectBufferCapacity(output)!=64 || !writable(env,state) || !writable(env,output) || action< -1 || action>7 || !std::isfinite(reward) || std::abs(reward)>1) return reject();
    auto* bytes=static_cast<std::uint8_t*>(env->GetDirectBufferAddress(state));auto* data=env->GetDirectBufferAddress(input);auto* out=env->GetDirectBufferAddress(output);
    auto overlap=[](void* a,std::size_t n,void* b,std::size_t m) { auto x=reinterpret_cast<std::uintptr_t>(a),y=reinterpret_cast<std::uintptr_t>(b);return x<y+m && y<x+n; };
    if(!bytes || !data || !out || overlap(bytes,2592,data,576) || overlap(bytes,2592,out,64) || overlap(data,576,out,64)) return reject();
    Model model{};Model::Base base{};Model::Input x{};
    if(reset==JNI_TRUE) { if(action!=-1) return reject();model.initialize(static_cast<std::uint64_t>(seed)); }
    else if(!model.load({bytes,2592})) return reject();
    std::memcpy(base.data(),data,512);std::memcpy(x.data(),static_cast<std::uint8_t*>(data)+512,64);
    if(!Model::inputs(base,x)) return reject();
    if(action>=0) {
        auto target=model.forward(base,x);target[action]=reward;double loss=0;
        if(!model.train(base,x,target,loss)) return reject();
    }
    const auto result=model.forward(base,x);model.save({bytes,2592});std::memcpy(out,result.data(),64);return JNI_TRUE;
}
