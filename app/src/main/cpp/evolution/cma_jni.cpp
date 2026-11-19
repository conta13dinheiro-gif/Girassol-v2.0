#include "cma_es.hpp"
#include <jni.h>
#include <cstring>
#include <algorithm>
#include <cmath>
extern "C" JNIEXPORT jboolean JNICALL Java_com_enhikyy_girassol_cognition_NativeCmaes_next(JNIEnv* env,jobject,jobject buffer,jobject scores,jlong seed,jboolean reset) {
    using namespace girassol::evolution;
    if(!buffer || env->GetDirectBufferCapacity(buffer)!=state_size*8) return JNI_FALSE;
    void* address=env->GetDirectBufferAddress(buffer); if(!address) return JNI_FALSE;
    std::array<double,state_size> data{}; std::memcpy(data.data(),address,sizeof(data));
    CmaState state;
    std::copy_n(data.begin(),8,state.mean.begin());
    if(reset) { if(!initialize(state,state.mean,static_cast<std::uint64_t>(seed))) return JNI_FALSE; }
    else {
        if(!scores || env->GetDirectBufferCapacity(scores)!=population*8) return JNI_FALSE;
        void* score_address=env->GetDirectBufferAddress(scores); if(!score_address) return JNI_FALSE;
        std::array<double,population> values{}; std::memcpy(values.data(),score_address,sizeof(values));
        std::copy_n(data.begin()+8,64,state.covariance.begin()); std::copy_n(data.begin()+72,8,state.sigma_path.begin()); std::copy_n(data.begin()+80,8,state.covariance_path.begin());
        state.sigma=data[88]; if(!(data[89]>=0 && data[89]<=1000000 && std::trunc(data[89])==data[89])) return JNI_FALSE;
        state.generation=static_cast<int>(data[89]);
        for(int k=0;k<population;++k) std::copy_n(data.begin()+90+k*8,8,state.candidates[k].begin());
        if(!advance(state,values,static_cast<std::uint64_t>(seed))) return JNI_FALSE;
    }
    std::copy(state.mean.begin(),state.mean.end(),data.begin()); std::copy(state.covariance.begin(),state.covariance.end(),data.begin()+8);
    std::copy(state.sigma_path.begin(),state.sigma_path.end(),data.begin()+72); std::copy(state.covariance_path.begin(),state.covariance_path.end(),data.begin()+80);
    data[88]=state.sigma; data[89]=state.generation;
    for(int k=0;k<population;++k) std::copy(state.candidates[k].begin(),state.candidates[k].end(),data.begin()+90+k*8);
    std::memcpy(address,data.data(),sizeof(data)); return JNI_TRUE;
}
