import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
android {
    namespace = "com.enhikyy.girassol"
    compileSdk = 35
    ndkVersion = "27.0.12077973"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    defaultConfig {
        applicationId = "com.enhikyy.girassol"
        minSdk = 33
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":research"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.8")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.filament:filament-android:1.56.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// CI supplies the pinned desktop compiler; local builds can use FILAMENT_TOOLS_DIR too.
val compileFilamentMaterials by tasks.registering(Exec::class) {
    val tools = providers.environmentVariable("FILAMENT_TOOLS_DIR")
    onlyIf { tools.isPresent }
    inputs.files(fileTree("src/main/materials") { include("*.mat") })
    outputs.dir("src/main/assets/materials")
    outputs.file("src/main/materials/city.sha256")
    commandLine("bash", rootProject.file("scripts/compile-materials.sh").absolutePath)
}
val verifyFilamentMaterial by tasks.registering {
    val manifest = file("src/main/materials/city.sha256")
    inputs.files(manifest, fileTree("src/main/materials") { include("*.mat") }, fileTree("src/main/assets/materials") { include("*.filamat") })
    dependsOn(compileFilamentMaterials)
    doLast {
        val sources=fileTree("src/main/materials") { include("*.mat") }.files
        val entries=manifest.readLines().filter { it.isNotBlank() }.map { it.split(Regex("\\s+"),limit=2)[1] }.toSet()
        sources.forEach { source->
            val relative=source.relativeTo(rootProject.projectDir).invariantSeparatorsPath
            check(relative in entries) { "Material sem compilação: $relative; defina FILAMENT_TOOLS_DIR (v1.56.0)" }
        }
        manifest.readLines().filter { it.isNotBlank() }.forEach { line ->
            val (expected, path) = line.split(Regex("\\s+"), limit = 2)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rootProject.file(path).readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Material desatualizado: execute scripts/compile-materials.sh com Filament v1.56.0" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyFilamentMaterial) }
