-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclasseswithmembernames class com.enhikyy.girassol.audio.NativeAudio {
    native <methods>;
}
-keepclasseswithmembernames class com.enhikyy.girassol.render.NativeProjection {
    native <methods>;
}
-keepclasseswithmembernames class com.enhikyy.girassol.render.NativeCityMesh {
    native <methods>;
}

-keepclasseswithmembernames class com.enhikyy.girassol.cognition.NativeCognition {
    native <methods>;
}
-keep class com.enhikyy.girassol.cognition.NativeMind { *; }

-keep class com.enhikyy.girassol.cognition.NativeMemory { *; }

-keep class com.enhikyy.girassol.cognition.NativeCmaes { *; }

-keep class com.enhikyy.girassol.cognition.NativeSemanticMemory { *; }
-keep class com.enhikyy.girassol.research.NonRootSwapArena { *; }

-keep class com.enhikyy.girassol.physics.NativeSpatial { *; }

# Shizuku loads this service by class name from a separate shell/root process.
-keep class com.enhikyy.girassol.privileged.TelemetryUserService { public <init>(); }
-keep interface com.enhikyy.girassol.privileged.IShizukuTelemetry { *; }

-keep class com.enhikyy.girassol.cognition.GeaNativeBridge { *; }
-keep class com.enhikyy.girassol.cognition.NativeAutonomy { *; }
