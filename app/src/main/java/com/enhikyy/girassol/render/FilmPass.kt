package com.enhikyy.girassol.render

import android.content.Context
import com.google.android.filament.*
import com.enhikyy.girassol.plugins.PluginRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Two views in one frame: the lit world into a target, then an original film-inspired finish. */
internal class FilmPass(private val engine: Engine, context: Context) : AutoCloseable {
    private val scene=engine.createScene()
    private val cameraEntity=EntityManager.get().create()
    private val camera=engine.createCamera(cameraEntity)
    val view=engine.createView().apply {
        scene=this@FilmPass.scene; camera=this@FilmPass.camera
        setPostProcessingEnabled(false)
    }
    private val entity=EntityManager.get().create()
    private val material: Material
    private val instance: MaterialInstance
    private val vertices: VertexBuffer
    private val indices: IndexBuffer
    private var color: Texture?=null
    private var depth: Texture?=null
    private var target: RenderTarget?=null
    private var width=0
    private var height=0
    private var closed=false
    init {
        PluginRegistry.initialize(context)
        val payload=context.assets.open("materials/girassol_dehancer_post.filamat").use { it.readBytes() }
        material=Material.Builder().payload(ByteBuffer.allocateDirect(payload.size).put(payload).apply { flip() },payload.size).build(engine)
        instance=material.createInstance()
        // Oversized triangle avoids diagonal interpolation seams and reads exactly one scene texture.
        val data=ByteBuffer.allocateDirect(3*20).order(ByteOrder.nativeOrder())
        for ((x,y) in listOf(-1f to -1f,3f to -1f,-1f to 3f)) data.putFloat(x).putFloat(y).putFloat(0f).putFloat((x+1)/2).putFloat((y+1)/2)
        data.flip()
        vertices=VertexBuffer.Builder().vertexCount(3).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,20)
            .attribute(VertexBuffer.VertexAttribute.UV0,0,VertexBuffer.AttributeType.FLOAT2,12,20).build(engine)
        indices=IndexBuffer.Builder().indexCount(3).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        vertices.setBufferAt(engine,0,data)
        indices.setBuffer(engine,ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder()).putShort(0).putShort(1).putShort(2).apply { flip() })
        RenderableManager.Builder(1).geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vertices,indices)
            .material(0,instance).boundingBox(Box(0f,0f,0f,1f,1f,1f)).culling(false).castShadows(false).receiveShadows(false).build(engine,entity)
        scene.addEntity(entity)
    }
    fun resize(world: com.google.android.filament.View,w: Int,h: Int) {
        if(closed || w<=0 || h<=0 || w==width && h==height) return
        world.renderTarget=null
        releaseTarget()
        width=w; height=h
        color=Texture.Builder().width(w).height(h).levels(1).format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE).build(engine)
        depth=Texture.Builder().width(w).height(h).levels(1).format(Texture.InternalFormat.DEPTH24)
            .usage(Texture.Usage.DEPTH_ATTACHMENT).build(engine)
        target=RenderTarget.Builder().texture(RenderTarget.AttachmentPoint.COLOR,color).texture(RenderTarget.AttachmentPoint.DEPTH,depth).build(engine)
        world.renderTarget=target
        instance.setParameter("sceneColor",color!!,TextureSampler(TextureSampler.MinFilter.LINEAR,TextureSampler.MagFilter.LINEAR,TextureSampler.WrapMode.CLAMP_TO_EDGE))
        instance.setParameter("texel",1f/w,1f/h)
        view.viewport=Viewport(0,0,w,h)
    }
    fun update(time: Float,enabled: Boolean) {
        instance.setParameter("time",time); instance.setParameter("amount",if(enabled) PluginRegistry.filmAmount() else 0f)
    }
    private fun releaseTarget() {
        target?.let(engine::destroyRenderTarget); target=null
        color?.let(engine::destroyTexture); color=null
        depth?.let(engine::destroyTexture); depth=null
    }
    override fun close() {
        if(closed) return
        closed=true
        engine.destroyView(view); releaseTarget()
        scene.removeEntity(entity); engine.destroyEntity(entity); EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vertices); engine.destroyIndexBuffer(indices)
        engine.destroyMaterialInstance(instance); engine.destroyMaterial(material)
        engine.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity)
        engine.destroyScene(scene)
    }
}
