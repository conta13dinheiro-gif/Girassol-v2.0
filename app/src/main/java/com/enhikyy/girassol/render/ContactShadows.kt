package com.enhikyy.girassol.render

import android.content.Context
import android.opengl.Matrix
import com.google.android.filament.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/** Shared quad buffers; one lightweight instance per body. No GPU allocation during animation. */
internal class ContactShadows(private val engine: Engine,private val scene: Scene,context: Context): AutoCloseable {
    private val material: Material
    private val vertices: VertexBuffer
    private val indices: IndexBuffer
    private data class Shadow(val entity: Int,val instance: MaterialInstance)
    private val bodies=mutableMapOf<String,Shadow>()
    private val matrix=FloatArray(16)
    init {
        val bytes=context.assets.open("materials/contact_shadow.filamat").use { it.readBytes() }
        material=Material.Builder().payload(ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() },bytes.size).build(engine)
        vertices=VertexBuffer.Builder().vertexCount(4).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,20)
            .attribute(VertexBuffer.VertexAttribute.UV0,0,VertexBuffer.AttributeType.FLOAT2,12,20).build(engine)
        val data=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder())
        for((x,z) in listOf(-1f to -1f,-1f to 1f,1f to 1f,1f to -1f))
            data.putFloat(x).putFloat(0f).putFloat(z).putFloat((x+1)/2).putFloat((z+1)/2)
        vertices.setBufferAt(engine,0,data.apply { flip() })
        indices=IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indices.setBuffer(engine,ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).apply {
            for(i in listOf(0,1,2,0,2,3)) putShort(i.toShort());flip()
        })
    }
    fun retain(ids: Set<String>) { bodies.keys.filter { it !in ids }.forEach(::remove) }
    fun update(id: String,x: Double,z: Double,surface: Double,height: Double) {
        val shadow=bodies.getOrPut(id) {
            val entity=EntityManager.get().create();val instance=material.createInstance()
            RenderableManager.Builder(1).geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vertices,indices)
                .material(0,instance).boundingBox(Box(0f,0f,0f,1f,.01f,1f))
                .castShadows(false).receiveShadows(false).build(engine,entity)
            if(!engine.transformManager.hasComponent(entity)) engine.transformManager.create(entity)
            scene.addEntity(entity);Shadow(entity,instance)
        }
        val separation=(height-surface).coerceAtLeast(0.0)
        val scale=(.34+separation*.16).toFloat()
        shadow.instance.setParameter("opacity",(.48*exp(-separation*2.5)).toFloat())
        Matrix.setIdentityM(matrix,0);Matrix.translateM(matrix,0,x.toFloat(),surface.toFloat()+.003f,z.toFloat())
        Matrix.scaleM(matrix,0,scale,1f,scale*.75f)
        engine.transformManager.setTransform(engine.transformManager.getInstance(shadow.entity),matrix)
    }
    private fun remove(id: String) {
        val s=bodies.remove(id) ?: return
        scene.removeEntity(s.entity);engine.destroyEntity(s.entity);EntityManager.get().destroy(s.entity);engine.destroyMaterialInstance(s.instance)
    }
    override fun close() {
        bodies.keys.toList().forEach(::remove)
        engine.destroyVertexBuffer(vertices);engine.destroyIndexBuffer(indices);engine.destroyMaterial(material)
    }
}
