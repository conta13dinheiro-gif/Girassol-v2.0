plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("junit:junit:4.13.2")
}

// Let the native/JVM integration script reuse Gradle's resolved dependencies.
tasks.register("writeHostClasspath") {
    val target=layout.buildDirectory.file("host-runtime-classpath.txt")
    inputs.files(configurations.runtimeClasspath)
    outputs.file(target)
    doLast { target.get().asFile.writeText(configurations.runtimeClasspath.get().asPath) }
}
