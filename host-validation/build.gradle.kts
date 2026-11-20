plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}
kotlin.sourceSets.main {
    kotlin.srcDir("../app/src/main/java")
    kotlin.include(
        "**/cognition/GeaNativeBridge.kt", "**/cognition/TsetlinReflexEngine.kt", "**/cognition/NativeMind.kt", "**/cognition/NativeAutonomy.kt",
        "**/cognition/NativeMemory.kt", "**/cognition/NativeCmaes.kt",
        "**/cognition/ShadowReplay.kt", "**/physics/NativeSpatial.kt",
        "**/render/NativeCityMesh.kt", "**/render/NativeProjection.kt"
    )
}
sourceSets.main { java.srcDir("../app/src/test/host") }
tasks.register("writeHostClasspath") {
    val target = layout.buildDirectory.file("runtime-classpath.txt")
    dependsOn(tasks.named("classes"))
    inputs.files(sourceSets.main.get().runtimeClasspath)
    outputs.file(target)
    doLast { target.get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath) }
}

val verifyGeaSqlite by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/test-gea-sqlite.py")
}
tasks.named("check") { dependsOn(verifyGeaSqlite) }

val verifyJniContract by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/test-jni-contract.py")
}
tasks.named("check") { dependsOn(verifyJniContract) }
