#include "forge/lora_trainer_realtime.hpp"
#include <cassert>
#include <iostream>
#include <limits>
#include <fstream>
#include <cstring>
#include <cstdlib>
#ifdef GIRASSOL_LORA_ALLOC_CHECK
static bool watch_allocations=false;
static int allocation_attempts=0;
extern "C" void* __real_malloc(std::size_t);
extern "C" void* __real_calloc(std::size_t,std::size_t);
extern "C" void* __wrap_malloc(std::size_t n) { if(watch_allocations) { ++allocation_attempts;return nullptr; } return __real_malloc(n); }
extern "C" void* __wrap_calloc(std::size_t n,std::size_t size) { if(watch_allocations) { ++allocation_attempts;return nullptr; } return __real_calloc(n,size); }
#endif
using Model=girassol::forge::RealTimeLoraAdapter;
static bool close(double a,double b,double eps=1e-9) { return std::abs(a-b)<eps; }
int main(int argc,char** argv) {
    Model model;model.initialize(1337);assert(model.valid());Model::Base base{};Model::Input x{},target{};
    for(int i=0;i<8;++i) { base[i*8+i]=.1;x[i]=(i+1)*.1;target[i]=.2-i*.03; }
    auto identity=model.forward(base,x);for(int i=0;i<8;++i) assert(identity[i]==base[i*8+i]*x[i]);
    // Nonzero B makes both derivatives observable. Central differences independently evaluate loss.
    for(int i=32;i<64;++i) model.weights[i]=.01*(i-48);
    model.anchor=model.weights;model.step=1;
    double loss=0;auto grad=model.gradients(base,x,target,loss);
    auto mse=[&](const Model& a) { auto y=a.forward(base,x);double l=0;for(int i=0;i<8;++i) l+=(y[i]-target[i])*(y[i]-target[i])/8;return l; };
    for(int i=0;i<64;++i) { auto plus=model,minus=model;plus.weights[i]+=1e-6;minus.weights[i]-=1e-6;assert(close(grad[i],(mse(plus)-mse(minus))/2e-6,1e-7)); }
    model.initialize(1337);grad=model.gradients(base,x,target,loss);
    const auto before=model;assert(model.train(base,x,target,loss));
    for(int i=0;i<64;++i) {
        const double lr=i<32?.001:.004;
        const double expected=before.weights[i]*(1-lr*.01)-lr*grad[i]/(std::abs(grad[i])+1e-8);
        assert(close(model.weights[i],expected));assert(close(model.fisher[i],.05*grad[i]*grad[i]));
    }
    // EWC uses PRE-step Fisher and anchor, not the updated Fisher or the regularized square.
    auto regular=model;regular.anchor.fill(0);auto unregular=regular;auto raw=regular.gradients(base,x,target,loss);auto prior=regular;
    assert(regular.train(base,x,target,loss,15));assert(unregular.train(base,x,target,loss,0));
    bool different=false;
    for(int i=0;i<64;++i) {
        const double g=raw[i]+15*prior.fisher[i]*prior.weights[i];
        assert(close(regular.m[i],.9*prior.m[i]+.1*g));assert(close(regular.fisher[i],.95*prior.fisher[i]+.05*raw[i]*raw[i]));
        different |= regular.weights[i]!=unregular.weights[i];
    }
    assert(different);
    model.initialize(1337);double initial=mse(model);for(int t=0;t<256;++t) assert(model.train(base,x,target,loss));
    assert(mse(model)<initial*.01);assert(model.consolidations==8 && model.anchor==model.weights);
    std::array<std::uint8_t,2592> wire{},restored_wire{};assert(model.save(wire));Model restored;assert(restored.load(wire));assert(restored.save(restored_wire));assert(wire==restored_wire);
    for(int t=0;t<40;++t) { assert(model.train(base,x,target,loss));assert(restored.train(base,x,target,loss)); }
    assert(model.save(wire) && restored.save(restored_wire) && wire==restored_wire);
    if(argc==3 && std::strcmp(argv[1],"export")==0) {
        std::ofstream file(argv[2],std::ios::binary);file.write(reinterpret_cast<const char*>(wire.data()),wire.size());return file?0:2;
    }
    if(argc==3 && std::strcmp(argv[1],"resume")==0) {
        std::array<std::uint8_t,2592> input{};std::ifstream file(argv[2],std::ios::binary);
        file.read(reinterpret_cast<char*>(input.data()),input.size());
        if(!file || file.peek()!=std::char_traits<char>::eof() || !restored.load(input)) return 2;
        for(int t=0;t<40;++t) { assert(model.train(base,x,target,loss));if(!restored.train(base,x,target,loss)) return 2; }
        assert(model.save(wire) && restored.save(restored_wire));return wire==restored_wire?0:3;
    }
#ifdef GIRASSOL_LORA_ALLOC_CHECK
    watch_allocations=true;
    for(int t=0;t<128;++t) { auto stack=model;assert(stack.load(wire));assert(stack.train(base,x,target,loss));assert(stack.save(restored_wire)); }
    watch_allocations=false;assert(allocation_attempts==0);
#endif
    auto sentinel=wire;auto bad=wire;bad[0]^=1;assert(!restored.load(bad));assert(restored.save(restored_wire) && restored_wire==sentinel);
    auto invalid=target;invalid[1]=std::numeric_limits<double>::quiet_NaN();assert(!model.train(base,x,invalid,loss));assert(model.save(restored_wire) && restored_wire==sentinel);
    invalid=x;invalid[0]=2;assert(!model.train(base,invalid,target,loss));
    bad=wire;for(int i=0;i<8;++i) bad[16+i]=255;assert(!restored.load(bad));
    assert(!restored.load(std::span<const std::uint8_t>{wire.data(),2591}));
    std::cout<<"LoRA+ 64 finite-difference gradients, exact AdamW/Fisher/EWC, convergence, 32-step anchors, bitwise optimizer resume, corrupt/invalid atomicity passed; state="<<sizeof(Model)<<" bytes\n";
}
