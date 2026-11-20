package com.enhikyy.girassol.cognition
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeCmaes {
    init { com.enhikyy.girassol.cognition.GeaNativeBridge.requireAvailable() }
    private external fun next(state: ByteBuffer,scores: ByteBuffer?,seed: Long,reset: Boolean): Boolean
    class Search(mean: List<Double>,private val seed: Long) {
        private val state=ByteBuffer.allocateDirect(170*8).order(ByteOrder.nativeOrder())
        init { require(mean.size==8 && mean.all { it.isFinite() && it in -2.0..2.0 }); mean.forEach(state::putDouble); check(next(state,null,seed,true)) }
        fun candidates(): List<List<Double>> = List(10) { k->List(8) { i->state.getDouble((90+k*8+i)*8) } }
        fun adapt(scores: List<Double>) {
            require(scores.size==10 && scores.all { it.isFinite() })
            val input=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder()); scores.forEach(input::putDouble)
            check(next(state,input,seed,false)) { "Distribuição CMA-ES inválida" }
        }
    }
}
