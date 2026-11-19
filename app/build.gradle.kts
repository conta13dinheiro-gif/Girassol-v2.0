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
        versionCode = 8
        versionName = "0.8.0"
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
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.filament:filament-android:1.56.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

// The matching precompiled material is shipped so Android builds need no desktop matc.
// Changing a material requires scripts/compile-materials.sh with Filament v1.56.0.
val verifyFilamentMaterial by tasks.registering {
    val manifest = file("src/main/materials/city.sha256")
    inputs.files(manifest, fileTree("src/main/materials") { include("*.mat") }, fileTree("src/main/assets/materials") { include("*.filamat") })
    doLast {
        manifest.readLines().filter { it.isNotBlank() }.forEach { line ->
            val (expected, path) = line.split(Regex("\\s+"), limit = 2)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(rootProject.file(path).readBytes()).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Material desatualizado: execute scripts/compile-materials.sh com Filament v1.56.0" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyFilamentMaterial) }
