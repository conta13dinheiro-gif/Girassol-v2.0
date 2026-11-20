import com.enhikyy.girassol.core.*
import com.enhikyy.girassol.physics.NativeSpatial

object SpaceAllocationIntegration {
    @JvmStatic fun main(args: Array<String>) {
        check(System.getenv("GIRASSOL_TEST_NAV_OOM")=="1")
        val world=WorldActor.genesis();val before=Canonical.state(world)
        val intentions=world.agents.associate {it.id to it.activity}
        var rejected=false
        try {NativeSpatial.advance(world,intentions)} catch(_: OutOfMemoryError) {rejected=true}
        check(rejected) {"Native arena allocation did not become a Java OutOfMemoryError"}
        check(Canonical.state(world)==before)
        val recovered=NativeSpatial.advance(world,intentions)
        check(recovered.applicable(world,intentions) && Canonical.state(world)==before)
        println("Real JNI arena bad_alloc -> Java OutOfMemoryError, immutable world and successful retry passed")
    }
}
