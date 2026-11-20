package com.enhikyy.girassol.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.enhikyy.girassol.core.Activity
import com.enhikyy.girassol.core.Citizen
import com.enhikyy.girassol.core.Room
import com.enhikyy.girassol.core.WorldState
import com.enhikyy.girassol.core.SpatialPoint
import com.enhikyy.girassol.core.SpatialPose
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import java.util.concurrent.Executors
import kotlin.math.*

/** Read-only viewport. Native geometry and sky convolution run on a worker; GPU ownership stays here. */
@SuppressLint("ViewConstructor")
internal class FilamentWorldView(
    context: Context,
    private val lifecycle: Lifecycle,
    var select: (String) -> Unit,
    private val onError: (String) -> Unit
) : FrameLayout(context), DefaultLifecycleObserver, Choreographer.FrameCallback {
    private val texture=TextureView(context)
    private val rig=OrbitCamera()
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"Girassol-scene").apply { priority=Thread.MIN_PRIORITY } }
    private val main=Handler(Looper.getMainLooper())
    private var engine: Engine?=null
    private var renderer: Renderer?=null
    private var scene: Scene?=null
    private var view: com.google.android.filament.View?=null
    private var camera: Camera?=null
    private var material: Material?=null
    private var glassMaterial: Material?=null
    private var film: FilmPass?=null
    private var contacts: ContactShadows?=null
    private var indirect: IndirectLight?=null
    private var skybox: Skybox?=null
    private var skyTexture: Texture?=null
    private var diffuseTexture: Texture?=null
    private var reflectionTexture: Texture?=null
    private var grading: ColorGrading?=null
    private var swapChain: SwapChain?=null
    private val helper=UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val entities=mutableListOf<Int>()
    private val meshes=mutableListOf<GpuMesh>()
    private val city=mutableListOf<GpuMesh>()
    private val roomEntities=mutableMapOf<Int,String>()
    private var rooms: List<Room>?=null
    private var seed=0L
    private var cityGeneration=0
    private var environmentPending=false
    private var environmentBucket=Long.MIN_VALUE
    private val actors=linkedMapOf<String,ActorVisual>()
    private val speechBubbles=linkedMapOf<String,TextView>()
    private var lastBubbleProjection=0L
    private var moving=false
    private var thermal=0
    private var resumed=false
    private var attached=false
    private var closed=false
    private var scheduled=false
    private var lastFrame=0L
    private var lastTick=Long.MIN_VALUE
    private var publishedAt=0L
    private var tickDuration=500.0
    private var zoomSetting=1f
    private var researchLight=0
    private var researching=false
    private var sunEntity=0
    private var follow: String?=null
    private var panX=0f
    private var panY=0f
    private var panning=false
    private val matrix=FloatArray(16)
    internal var renderedFrames=0L; private set
    internal val liveMeshCount get()=meshes.size
    internal val isClosed get()=closed
    private data class GpuMesh(val entity: Int,val vertices: VertexBuffer,val indices: IndexBuffer,val instance: MaterialInstance,val glass: MaterialInstance?)
    private data class ActorVisual(val color: Long,var role: Int,var x: Double,var z: Double,
        var fromX: Double=x,var fromZ: Double=z,var targetX: Double=x,var targetZ: Double=z,
        var changedAt: Long=0,var walking: Boolean=false,var yaw: Double=0.0,var targetYaw: Double=0.0,
        var activity: Activity=Activity.TALK,var acting: Boolean=false,var mesh: GpuMesh?=null,var revision: Int=0,
        var y: Double=0.0,var ground: Double=0.0,var route: List<SpatialPoint> = emptyList(),
        var stride: Double=0.0,var lean: Double=0.0)
    private val scaleDetector=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { rig.scale(d.scaleFactor.toDouble()); return true }
    })
    private val gestures=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { rig.stop(); return true }
        override fun onScroll(e1: MotionEvent?,e2: MotionEvent,dx: Float,dy: Float): Boolean {
            if(e2.pointerCount==1 && !scaleDetector.isInProgress) { follow=null; rig.orbit(dx*.006,dy*.004) }
            return true
        }
        override fun onFling(e1: MotionEvent?,e2: MotionEvent,vx: Float,vy: Float): Boolean {
            if(!panning) rig.fling(-vx*.0007,-vy*.0005)
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Actual visible fragment/entity; walls correctly occlude citizens behind them.
            view?.pick(e.x.toInt().coerceIn(0,max(0,texture.width-1)),
                (texture.height-1-e.y.toInt()).coerceIn(0,max(0,texture.height-1)),main) { hit ->
                if(!closed) {
                    val agent=actors.entries.firstOrNull { it.value.mesh?.entity==hit.renderable }
                    if(agent!=null) { follow=agent.key; rig.focus(agent.value.x,agent.value.z,.65,true); select(agent.key) }
                    else roomEntities[hit.renderable]?.let { id->
                        rooms?.firstOrNull { it.id==id }?.let { r-> follow=null; rig.focus(r.x,r.z,.6,true); select("room:$id") }
                    }
                }
            }
            performClick(); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { follow=null; recenter(); return true }
    })
    init {
        contentDescription="Mundo 3D. Um dedo gira, dois dedos deslocam, pinça aproxima; toque para inspecionar."
        addView(texture,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycle.addObserver(this)
        try { initialize() } catch(e: Exception) { fail(e.message?:"Não foi possível abrir o mundo 3D") }
        catch(_: LinkageError) { fail("Renderização 3D indisponível neste dispositivo") }
    }
    private fun initialize() {
        Filament.init()
        val e=Engine.create(Engine.Backend.OPENGL).also { engine=it }
        renderer=e.createRenderer().apply { clearOptions=Renderer.ClearOptions().apply { clear=true; clearColor=floatArrayOf(.02f,.03f,.045f,1f) } }
        scene=e.createScene()
        camera=e.createCamera(entity()).apply { setExposure(16f,1f/125f,100f) }
        view=e.createView().apply {
            scene=this@FilamentWorldView.scene; camera=this@FilamentWorldView.camera
            antiAliasing=com.google.android.filament.View.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions=com.google.android.filament.View.MultiSampleAntiAliasingOptions().apply { enabled=true; sampleCount=2 }
            setShadowType(com.google.android.filament.View.ShadowType.DPCF)
            softShadowOptions=com.google.android.filament.View.SoftShadowOptions().apply { penumbraScale=1.2f }
            bloomOptions=com.google.android.filament.View.BloomOptions().apply { enabled=true; strength=.045f; resolution=256; levels=5 }
            ambientOcclusionOptions=com.google.android.filament.View.AmbientOcclusionOptions().apply { enabled=true; radius=.5f; intensity=1.1f }
        }
        val bytes=context.assets.open("materials/city.filamat").use { it.readBytes() }
        material=Material.Builder().payload(java.nio.ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() },bytes.size).build(e)
        val glassBytes=context.assets.open("materials/glass.filamat").use { it.readBytes() }
        glassMaterial=Material.Builder().payload(java.nio.ByteBuffer.allocateDirect(glassBytes.size).put(glassBytes).apply { flip() },glassBytes.size).build(e)
        film=FilmPass(e,context)
        contacts=ContactShadows(e,checkNotNull(scene),context)
        grading=ColorGrading.Builder().toneMapper(ToneMapper.ACES()).build(e)
        view!!.colorGrading=grading
        indirect=IndirectLight.Builder().irradiance(1,floatArrayOf(.23f,.3f,.4f)).intensity(26000f).build(e)
        scene!!.indirectLight=indirect
        sunEntity=entity()
        LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1f,.92f,.78f).intensity(75000f)
            .direction(-.5f,-1f,-.4f).castShadows(true)
            .shadowOptions(LightManager.ShadowOptions().apply { mapSize=1024; shadowFar=90f; shadowCascades=2; normalBias=.4f; constantBias=.001f })
            .build(e,sunEntity)
        scene!!.addEntity(sunEntity)
        researchLight=entity()
        LightManager.Builder(LightManager.Type.POINT).color(.12f,.7f,1f).intensity(0f).falloff(5f).castShadows(false).build(e,researchLight)
        scene!!.addEntity(researchLight)
        helper.renderCallback=object: UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) { swapChain?.let { e.destroySwapChain(it) }; swapChain=e.createSwapChain(surface) }
            override fun onDetachedFromSurface() { swapChain?.let { e.destroySwapChain(it); e.flushAndWait() }; swapChain=null }
            override fun onResized(width: Int,height: Int) { view?.viewport=Viewport(0,0,width,height); updateCamera(width,height); view?.let { film?.resize(it,width,height) }; e.flushAndWait() }
        }
        helper.attachTo(texture)
    }
    fun focus(id: String?) {
        if(id==null) { follow=null; return }
        actors[id]?.let { follow=id; rig.focus(it.x,it.z,.65,true) }
        if(id.startsWith("room:")) rooms?.firstOrNull { it.id==id.removePrefix("room:") }?.let { follow=null; rig.focus(it.x,it.z,.6,true) }
    }
    private fun recenter() {
        val r=rooms.orEmpty(); if(r.isEmpty()) return
        rig.focus((r.minOf { it.x-it.width/2 }+r.maxOf { it.x+it.width/2 })/2,
            (r.minOf { it.z-it.depth/2 }+r.maxOf { it.z+it.depth/2 })/2)
        rig.reset()
    }
    fun update(world: WorldState,running: Boolean,thermalStatus: Int,zoom: Float,researchBusy: Boolean=false) {
        if(closed) return
        moving=running
        researching=researchBusy
        if(thermal!=thermalStatus) {
            thermal=thermalStatus
            view?.ambientOcclusionOptions=com.google.android.filament.View.AmbientOcclusionOptions().apply { enabled=thermal<3; radius=.5f }
            view?.bloomOptions=com.google.android.filament.View.BloomOptions().apply { enabled=thermal<3; strength=.045f; resolution=256; levels=5 }
        }
        if(rooms!=world.rooms || seed!=world.seed) {
            rooms=world.rooms.toList(); seed=world.seed
            val generation=++cityGeneration; val input=rooms!!; val worldSeed=seed
            rig.extent=hypot(input.maxOf { it.x+it.width/2 }-input.minOf { it.x-it.width/2 },input.maxOf { it.z+it.depth/2 }-input.minOf { it.z-it.depth/2 })*.44
            if(city.isEmpty()) recenter()
            worker.execute {
                try {
                    val ground=NativeCityMesh.city(input,worldSeed,3)
                    val buildings=input.map { it.id to NativeCityMesh.city(listOf(it),worldSeed,2) }
                    main.post {
                        if(!closed && generation==cityGeneration) guarded {
                            val replacement=mutableListOf<GpuMesh>()
                            replacement+=upload(ground)
                            val mapping=mutableMapOf<Int,String>()
                            buildings.forEach { (id,data)-> val mesh=upload(data); replacement+=mesh; mapping[mesh.entity]=id }
                            city.toList().forEach(::destroyMesh); city.clear(); city.addAll(replacement)
                            roomEntities.clear(); roomEntities.putAll(mapping)
                        }
                    }
                } catch(_: InterruptedException) { Thread.currentThread().interrupt() }
                catch(e: Exception) { main.post { if(!closed && generation==cityGeneration) fail(e.message?:"Falha na paisagem") } }
            }
        }
        val ids=world.agents.map { it.id }.toSet()
        contacts?.retain(ids.toSet())
        actors.keys.filter { it !in ids }.forEach { actors.remove(it)?.mesh?.let(::destroyMesh) }
        val now=SystemClock.uptimeMillis()
        syncSpeechBubbles(world)
        if(world.tick!=lastTick) {
            if(publishedAt>0 && world.tick>lastTick) tickDuration=((now-publishedAt).toDouble()/(world.tick-lastTick)).coerceIn(80.0,2000.0)
            publishedAt=now
        }
        world.agents.forEach { c->
            val role=when(c.activity) { Activity.STUDY,Activity.WRITE->1; Activity.BUILD->2; Activity.COFFEE,Activity.EAT->3; else->0 }
            val a=actors.getOrPut(c.id) { ActorVisual(c.color,role,c.x,c.z) }
            if(a.mesh==null && a.revision==0 || a.role!=role) {
                a.role=role; val revision=++a.revision
                worker.execute {
                    try {
                        val data=NativeCityMesh.citizen(c.color,role)
                        main.post { if(!closed && actors[c.id]===a && a.revision==revision) guarded {
                            val replacement=upload(data); a.mesh?.let(::destroyMesh); a.mesh=replacement
                            replacement.instance.setParameter("phase",(c.id.hashCode()%100)*.13f)
                        } }
                    } catch(e: Exception) { main.post { if(!closed) fail(e.message?:"Falha no habitante") } }
                }
            }
            if(world.tick!=lastTick) setTarget(a,c,world.spatial[c.id],now)
        }
        lastTick=world.tick
        if(zoom!=zoomSetting) { rig.scale((zoom/zoomSetting).toDouble()); zoomSetting=zoom }
        requestEnvironment(world.tick,world.seed)
        schedule()
    }
    private fun syncSpeechBubbles(world: WorldState) {
        val recent = world.dialogues.asReversed().take(24)
        val latestBySpeaker = recent.filter { it.tick >= world.tick - 12 }
            .associateBy { it.speaker }
        val visibleIds = latestBySpeaker.keys
        speechBubbles.keys.filter { it !in visibleIds || actors[it] == null }.forEach { id ->
            speechBubbles.remove(id)?.let(::removeView)
        }
        latestBySpeaker.forEach { (id, dialogue) ->
            val bubble = speechBubbles.getOrPut(id) {
                TextView(context).apply {
                    textSize = 10f
                    setTextColor(Color.rgb(242, 239, 226))
                    maxWidth = dp(184)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(9), dp(6), dp(9), dp(6))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(11).toFloat()
                        setColor(Color.argb(224, 12, 18, 16))
                        setStroke(dp(1), Color.argb(150, 213, 177, 100))
                    }
                    elevation = dp(4).toFloat()
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = "Fala do habitante"
                    isClickable = false
                    isFocusable = false
                }.also { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
            }
            val citizen = world.agents.firstOrNull { it.id == id }
            bubble.text = dialogue.portuguese.take(112)
            bubble.contentDescription = citizen?.let { "Fala de ${it.name}: ${dialogue.portuguese}" } ?: "Fala do habitante"
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    /** Project a world-space head point through the same orbit rig that drives Filament. */
    private fun projectHead(x: Double, y: Double, z: Double): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val aspect = width.toDouble() / height
        val eye = rig.eye(aspect)
        var fx = rig.centerX - eye[0]
        var fy = rig.centerY - eye[1]
        var fz = rig.centerZ - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9)
        fx /= fl; fy /= fl; fz /= fl
        var rx = -fz
        var rz = fx
        val rl = hypot(rx, rz).coerceAtLeast(1e-9)
        rx /= rl; rz /= rl
        val ux = -fy * rz
        val uy = fz * rx - fx * rz
        val uz = fy * rx
        val dx = x - eye[0]
        val dy = y - eye[1]
        val dz = z - eye[2]
        val depth = dx * fx + dy * fy + dz * fz
        if (depth <= .2) return null
        val tangent = tan(Math.toRadians(rig.fov) * .5).coerceAtLeast(1e-6)
        val ndcX = (dx * rx + dz * rz) / (depth * tangent * aspect)
        val ndcY = (dx * ux + dy * uy + dz * uz) / (depth * tangent)
        if (abs(ndcX) > 1.2 || abs(ndcY) > 1.2) return null
        return (((ndcX + 1.0) * .5 * width).roundToInt()) to (((1.0 - ndcY) * .5 * height).roundToInt())
    }

    private fun layoutSpeechBubbles(now: Long) {
        if (now - lastBubbleProjection < 90L) return
        lastBubbleProjection = now
        speechBubbles.forEach { (id, bubble) ->
            val actor = actors[id]
            val point = actor?.let { projectHead(it.x, it.y + 1.92, it.z) }
            if (point == null) {
                bubble.visibility = GONE
            } else {
                bubble.measure(
                    MeasureSpec.makeMeasureSpec(dp(192), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(dp(74), MeasureSpec.AT_MOST)
                )
                val bubbleWidth = bubble.measuredWidth.coerceAtLeast(dp(36))
                val bubbleHeight = bubble.measuredHeight.coerceAtLeast(dp(24))
                val left = (point.first - bubbleWidth / 2).coerceIn(0, max(0, width - bubbleWidth))
                val top = (point.second - bubbleHeight - dp(12)).coerceIn(dp(8), max(dp(8), height - bubbleHeight))
                bubble.layout(left, top, left + bubbleWidth, top + bubbleHeight)
                bubble.visibility = VISIBLE
            }
        }
    }

    private fun requestEnvironment(tick: Long,worldSeed: Long) {
        val bucket=tick/12
        if(environmentPending || environmentBucket==bucket) return
        environmentPending=true
        worker.execute {
            try {
                val data=Atmosphere.generate(tick,worldSeed)
                main.post { if(!closed) guarded { applyEnvironment(data); environmentBucket=bucket; environmentPending=false } }
            } catch(_: InterruptedException) { Thread.currentThread().interrupt() }
            catch(e: Exception) { main.post { if(!closed) fail(e.message?:"Falha na atmosfera") } }
        }
    }
    private fun applyEnvironment(data: Atmosphere.Environment) {
        val e=engine?:return
        fun cube(size: Int,levels: Int)=Texture.Builder().width(size).height(size).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP).format(Texture.InternalFormat.RGB16F).build(e)
        fun upload(texture: Texture,level: Int,cube: Atmosphere.Cube) {
            @Suppress("DEPRECATION")
            texture.setImage(e,level,Texture.PixelBufferDescriptor(cube.pixels,Texture.Format.RGB,Texture.Type.FLOAT),IntArray(6) { it*cube.size*cube.size*12 })
        }
        if(skyTexture==null) {
            skyTexture=cube(64,1); diffuseTexture=cube(8,1); reflectionTexture=cube(32,6)
            skybox=Skybox.Builder().environment(skyTexture!!).intensity(26000f).build(e)
            scene!!.skybox=skybox
            scene!!.indirectLight=null
            indirect?.let { e.destroyIndirectLight(it) }
            indirect=IndirectLight.Builder().reflections(reflectionTexture!!).irradiance(diffuseTexture!!).intensity(26000f).build(e)
            scene!!.indirectLight=indirect
        }
        upload(skyTexture!!,0,data.sky); upload(diffuseTexture!!,0,data.irradiance)
        data.reflections.forEachIndexed { level,cube->upload(reflectionTexture!!,level,cube) }
    }
    private fun setTarget(a: ActorVisual,c: Citizen,pose: SpatialPose?,now: Long) {
        val initial=a.changedAt==0L
        a.fromX=a.x; a.fromZ=a.z; a.targetX=c.x; a.targetZ=c.z; a.changedAt=now
        if(pose!=null) {
            a.route=pose.route;a.ground=pose.surface
            if(initial || pose.corrected) { a.x=pose.route.first().x;a.z=pose.route.first().z;a.y=pose.route.first().y }
        } else {
            val floor=if(rooms.orEmpty().any { abs(c.x-it.x)<=it.width/2 && abs(c.z-it.z)<=it.depth/2 }) .175 else 0.0
            a.ground=floor;a.route=listOf(SpatialPoint(a.x,a.y,a.z),SpatialPoint(c.x,floor,c.z))
        }
        val dx=c.x-a.x; val dz=c.z-a.z
        a.walking=hypot(dx,dz)>.001
        if(a.walking) a.targetYaw=atan2(dx,dz)
        a.activity=c.activity; a.acting=c.acting
    }
    private fun upload(data: NativeCityMesh.Data): GpuMesh {
        val e=checkNotNull(engine)
        val vb=VertexBuffer.Builder().vertexCount(data.vertexCount).bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,32)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS,0,VertexBuffer.AttributeType.FLOAT4,12,32)
            .attribute(VertexBuffer.VertexAttribute.COLOR,0,VertexBuffer.AttributeType.UBYTE4,28,32)
            .normalized(VertexBuffer.VertexAttribute.COLOR).build(e)
        val ib=try { IndexBuffer.Builder().indexCount(data.indexCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(e) }
            catch(t: Exception) { e.destroyVertexBuffer(vb); throw t }
        val instance=try { checkNotNull(material).createInstance() } catch(t: Exception) { e.destroyIndexBuffer(ib); e.destroyVertexBuffer(vb); throw t }
        val glass=try { if(data.opaqueCount<data.indexCount) glassMaterial!!.createInstance() else null }
            catch(t: Exception) { e.destroyMaterialInstance(instance); e.destroyIndexBuffer(ib); e.destroyVertexBuffer(vb); throw t }
        val id=entity(); val mesh=GpuMesh(id,vb,ib,instance,glass).also { meshes+=it }
        glass?.setParameter("time",0f); glass?.setParameter("night",0f)
        instance.setParameter("time",0f); instance.setParameter("night",0f); instance.setParameter("gait",0f)
        instance.setParameter("activity",0f); instance.setParameter("phase",0f);instance.setParameter("lean",0f)
        instance.setParameter("institutionalMix",.56f)
        instance.setParameter("lumonTint",.78f,.91f,.84f,1.0f)
        instance.setParameter("tropicalTint",1.12f,.83f,.62f,1.0f)
        vb.setBufferAt(e,0,data.vertices); ib.setBuffer(e,data.indices)
        val lo=FloatArray(3) { Float.POSITIVE_INFINITY }; val hi=FloatArray(3) { Float.NEGATIVE_INFINITY }
        repeat(data.vertexCount) { i-> repeat(3) { axis->val x=data.vertices.getFloat(i*32+axis*4); lo[axis]=min(lo[axis],x); hi[axis]=max(hi[axis],x) } }
        val builder=RenderableManager.Builder(if(glass!=null) 2 else 1).boundingBox(Box((lo[0]+hi[0])/2,(lo[1]+hi[1])/2,(lo[2]+hi[2])/2,
            (hi[0]-lo[0])/2+.35f,(hi[1]-lo[1])/2+.35f,(hi[2]-lo[2])/2+.5f))
            .material(0,instance).geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vb,ib,0,data.opaqueCount)
            .castShadows(true).receiveShadows(true)
        if(glass!=null) builder.material(1,glass).geometry(1,RenderableManager.PrimitiveType.TRIANGLES,vb,ib,data.opaqueCount,data.indexCount-data.opaqueCount)
        builder.build(e,id)
        if(!e.transformManager.hasComponent(id)) e.transformManager.create(id)
        scene!!.addEntity(id); return mesh
    }
    private fun entity()=EntityManager.get().create().also { entities+=it }
    private fun destroyMesh(mesh: GpuMesh) {
        val e=engine?:return
        scene?.removeEntity(mesh.entity); e.destroyEntity(mesh.entity); EntityManager.get().destroy(mesh.entity); entities.remove(mesh.entity)
        mesh.glass?.let { e.destroyMaterialInstance(it) }; e.destroyVertexBuffer(mesh.vertices); e.destroyIndexBuffer(mesh.indices); e.destroyMaterialInstance(mesh.instance); meshes.remove(mesh)
    }
    private fun updateCamera(width: Int=texture.width,height: Int=texture.height) {
        if(width<=0 || height<=0) return
        val aspect=width.toDouble()/height
        camera?.setProjection(rig.fov,aspect,.1,3000.0,Camera.Fov.VERTICAL)
        val eye=rig.eye(aspect)
        camera?.lookAt(eye[0],eye[1],eye[2],rig.centerX,rig.centerY,rig.centerZ,0.0,1.0,0.0)
    }
    override fun doFrame(frameTimeNanos: Long) {
        scheduled=false
        if(closed || !attached || !resumed) return
        schedule()
        if(lastFrame!=0L && frameTimeNanos-lastFrame<(if(thermal>=3) 32_000_000L else 12_000_000L)) return
        val dt=if(lastFrame==0L) 1.0/60 else ((frameTimeNanos-lastFrame)/1e9).coerceIn(.001,.1)
        lastFrame=frameTimeNanos
        if(!helper.isReadyToRender) return
        guarded {
            val e=engine?:return@guarded; val chain=swapChain?:return@guarded
            val now=SystemClock.uptimeMillis()
            val t=if(moving) ((now-publishedAt)/tickDuration).coerceIn(0.0,1.0) else 0.0
            val sun=Atmosphere.sun(lastTick.toDouble()+t,seed)
            val lm=e.lightManager; val light=lm.getInstance(sunEntity)
            val night=sun.night
            lm.setIntensity(light,if(sun.daylight>.01) 85000f*sun.daylight*(1-sun.cloud*.45f) else 850f)
            val sign=if(sun.y>0) -1 else 1
            lm.setDirection(light,(sign*sun.x).toFloat(),-max(.08,abs(sun.y)).toFloat(),(sign*sun.z).toFloat())
            lm.setColor(light,1f-.45f*night,.82f+.04f*night,.64f+.36f*night)
            // Maintain night readability through the physical exposure, while retaining emissive contrast.
            camera?.setExposure(16f,1f/125f,100f+night*500f)
            val visualTime=(now%600000)/1000f
            val research=lm.getInstance(researchLight)
            val library=rooms?.firstOrNull { it.id=="library" }
            if(library!=null) lm.setPosition(research,library.x.toFloat(),1.7f,library.z.toFloat())
            lm.setIntensity(research,if(researching && library!=null) 1800f+1600f*(.5f+.5f*sin(visualTime*2.4f)) else 0f)
            city.forEach { it.instance.setParameter("time",visualTime); it.instance.setParameter("night",night); it.glass?.setParameter("time",visualTime); it.glass?.setParameter("night",night) }
            actors.forEach { (id,a)-> a.mesh?.let { mesh->
                val progress=((now-a.changedAt)/tickDuration).coerceIn(0.0,1.0)
                val previousX=a.x;val previousZ=a.z
                // Follow the recorded fixed-step polyline, never a chord through interior corners.
                if(a.route.isNotEmpty()) {
                    val sample=progress*(a.route.size-1);val index=sample.toInt().coerceAtMost(a.route.lastIndex)
                    val first=a.route[index];val second=a.route[(index+1).coerceAtMost(a.route.lastIndex)];val f=sample-index
                    a.x=first.x+(second.x-first.x)*f;a.y=first.y+(second.y-first.y)*f;a.z=first.z+(second.z-first.z)*f
                    if(hypot(second.x-first.x,second.z-first.z)>1e-6) a.targetYaw=atan2(second.x-first.x,second.z-first.z)
                }
                val traveled=hypot(a.x-previousX,a.z-previousZ)
                val walking=moving && traveled>1e-6 && progress<1
                if(walking) a.stride=(a.stride+traveled*(2*PI/.68))%(2*PI)
                val turn=atan2(sin(a.targetYaw-a.yaw),cos(a.targetYaw-a.yaw))
                a.yaw+=turn*(1-exp(-12*dt));a.lean+=((if(walking) turn.coerceIn(-.12,.12) else 0.0)-a.lean)*(1-exp(-10*dt))
                mesh.instance.setParameter("time",visualTime); mesh.instance.setParameter("night",night)
                mesh.instance.setParameter("phase",a.stride.toFloat());mesh.instance.setParameter("lean",a.lean.toFloat())
                mesh.instance.setParameter("gait",if(walking) (traveled/dt/.9).coerceIn(0.0,1.0).toFloat() else 0f)
                mesh.instance.setParameter("activity",if(moving && a.acting) when(a.activity) { Activity.TALK->1f; Activity.WRITE,Activity.STUDY->2f; Activity.BUILD->3f; else->0f } else 0f)
                Matrix.setIdentityM(matrix,0); Matrix.translateM(matrix,0,a.x.toFloat(),a.y.toFloat(),a.z.toFloat())
                Matrix.rotateM(matrix,0,Math.toDegrees(a.yaw).toFloat(),0f,1f,0f)
                contacts?.update(id,a.x,a.z,a.ground,a.y)
                e.transformManager.setTransform(e.transformManager.getInstance(mesh.entity),matrix)
            } }
            follow?.let { id-> actors[id]?.let { rig.focus(it.x,it.z,.65) } }
            rig.advance(dt); updateCamera()
            layoutSpeechBubbles(now)
            val r=renderer?:return@guarded
            if(r.beginFrame(chain,frameTimeNanos)) { film?.update(visualTime,thermal<3); r.render(checkNotNull(view)); film?.let { r.render(it.view) }; r.endFrame(); renderedFrames++ }
        }
    }
    private inline fun guarded(block: ()->Unit) { try { block() } catch(e: Exception) { fail(e.message?:"A cena 3D foi interrompida") } }
    private fun schedule() { if(!scheduled && attached && resumed && !closed) { scheduled=true; Choreographer.getInstance().postFrameCallback(this) } }
    private fun unschedule() { Choreographer.getInstance().removeFrameCallback(this); scheduled=false; lastFrame=0 }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached=true; schedule() }
    override fun onDetachedFromWindow() { attached=false; unschedule(); super.onDetachedFromWindow() }
    override fun onResume(owner: LifecycleOwner) { resumed=true; schedule() }
    override fun onPause(owner: LifecycleOwner) { resumed=false; unschedule() }
    override fun onDestroy(owner: LifecycleOwner)=close()
    override fun onInterceptTouchEvent(event: MotionEvent)=true
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked!=MotionEvent.ACTION_UP && event.actionMasked!=MotionEvent.ACTION_CANCEL)
        scaleDetector.onTouchEvent(event)
        if(event.pointerCount>=2) {
            val x=(event.getX(0)+event.getX(1))/2; val y=(event.getY(0)+event.getY(1))/2
            if(panning && event.actionMasked==MotionEvent.ACTION_MOVE) { follow=null; rig.pan((x-panX).toDouble(),(y-panY).toDouble(),width,height) }
            panX=x; panY=y; panning=true
        } else if(event.actionMasked==MotionEvent.ACTION_DOWN) panning=false
        gestures.onTouchEvent(event); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun fail(message: String) { if(closed) return; close(); main.post { onError(message) } }
    fun close() {
        if(closed) return
        closed=true; unschedule(); lifecycle.removeObserver(this); worker.shutdownNow()
        helper.detach()
        val e=engine?:return
        meshes.toList().forEach(::destroyMesh); actors.clear(); speechBubbles.values.toList().forEach(::removeView); speechBubbles.clear(); city.clear(); roomEntities.clear()
        entities.forEach { e.destroyCameraComponent(it); e.destroyEntity(it); EntityManager.get().destroy(it) }; entities.clear()
        view?.renderTarget=null
        contacts?.close();contacts=null
        film?.close(); film=null
        view?.let { e.destroyView(it) }
        scene?.indirectLight=null; scene?.skybox=null
        indirect?.let { e.destroyIndirectLight(it) }; skybox?.let { e.destroySkybox(it) }
        listOfNotNull(skyTexture,diffuseTexture,reflectionTexture).forEach { e.destroyTexture(it) }
        glassMaterial?.let { e.destroyMaterial(it) }; material?.let { e.destroyMaterial(it) }; grading?.let { e.destroyColorGrading(it) }
        scene?.let { e.destroyScene(it) }; renderer?.let { e.destroyRenderer(it) }
        e.destroy(); engine=null
    }
}
