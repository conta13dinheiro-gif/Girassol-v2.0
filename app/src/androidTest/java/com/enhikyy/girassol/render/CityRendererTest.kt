package com.enhikyy.girassol.render

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.enhikyy.girassol.core.Canonical
import com.enhikyy.girassol.core.Room
import com.enhikyy.girassol.core.WorldActor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CityRendererTest {
    @Test fun nativeGeometryIsDeterministicAndNeverChangesLogicalState() {
        val world=WorldActor.genesis()
        val before=Canonical.state(world)
        val a=NativeCityMesh.city(world.rooms,world.seed)
        val b=NativeCityMesh.city(world.rooms,world.seed)
        assertEquals(a.vertexCount,b.vertexCount)
        assertEquals(a.vertices,b.vertices); assertEquals(a.indices,b.indices)
        assertEquals(before,Canonical.state(world))
        val actor=NativeCityMesh.citizen(world.agents.first().color)
        assertTrue(actor.vertexCount>500)
        assertEquals(actor.vertexCount*NativeCityMesh.STRIDE,actor.vertices.capacity())
    }

    @Test fun drawsReplacesCityPausesAndReleasesAcrossRecreation() {
        val error=AtomicReference<String?>()
        var world=WorldActor.genesis()
        lateinit var render: FilamentWorldView
        ActivityScenario.launch(RendererTestActivity::class.java).use { scenario ->
            fun mount() {
                scenario.onActivity { activity ->
                    render=FilamentWorldView(activity,activity.lifecycle,{},error::set)
                    activity.setContentView(render)
                    render.update(world,true,0,1f)
                }
            }
            fun frames(): Long { var count=0L; scenario.onActivity { count=render.renderedFrames }; return count }
            fun awaitFrame(after: Long) {
                val deadline=System.currentTimeMillis()+8000
                while (frames()<=after && error.get()==null && System.currentTimeMillis()<deadline) Thread.sleep(50)
                assertNull(error.get()); assertTrue("Filament did not present a frame",frames()>after)
            }
            fun awaitMeshes() {
                val expected=world.rooms.size+world.agents.size+1
                val deadline=System.currentTimeMillis()+15000
                var count=0
                do {
                    scenario.onActivity { count=render.liveMeshCount }
                    if (count==expected || error.get()!=null) break
                    Thread.sleep(50)
                } while(System.currentTimeMillis()<deadline)
                assertNull(error.get()); assertEquals("Scene replacements must release old geometry",expected,count)
            }
            mount(); awaitFrame(0); awaitMeshes()
            // Real engines/resources are rebuilt. A device without Vulkan exercises ES startup;
            // Vulkan devices additionally exercise recovery from a surface/frame failure.
            var vulkan=false
            scenario.onActivity { vulkan=render.graphicsBackend==com.google.android.filament.Engine.Backend.VULKAN }
            if(vulkan) {
                val before=frames()
                scenario.onActivity {
                    val failure=FilamentWorldView::class.java.getDeclaredMethod("fail",String::class.java)
                    failure.isAccessible=true;failure.invoke(render,"Injected surface failure")
                    assertEquals(com.google.android.filament.Engine.Backend.OPENGL,render.graphicsBackend)
                    assertFalse(render.isClosed)
                }
                awaitFrame(before);awaitMeshes()
            }
            repeat(3) { i ->
                world=world.copy(rooms=world.rooms+Room("test_$i","Anexo",2.0+i*2,9.0,1.7,1.5,2))
                val before=frames()
                scenario.onActivity {
                    render.update(world,true,3,1.35f)
                }
                awaitFrame(before); awaitMeshes()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val paused=frames(); Thread.sleep(150); assertEquals(paused,frames())
            scenario.moveToState(Lifecycle.State.RESUMED); awaitFrame(paused)
            scenario.onActivity { render.close(); render.close(); assertEquals(0,render.liveMeshCount); assertTrue(render.isClosed) }
            mount(); awaitFrame(0)
            scenario.recreate() // Lifecycle destroys the engine even when Compose did not release it.
            assertTrue(render.isClosed)
            mount(); awaitFrame(0)
        }
        assertTrue(render.isClosed)
    }
}
