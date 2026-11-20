package com.enhikyy.girassol.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHardwareSnapshotTest {
    @Test
    fun parsesCpuFeaturesAndClusters() {
        val snapshot = NativeHardwareSnapshot.parse(
            """
            abi=arm64-v8a
            neon=true
            dotprod=true
            i8mm=false
            sve2=false
            avx2=false
            opencl_loader=true
            vulkan_loader=true
            logical_cpus=8
            big_cores=6,7
            little_cores=0,1,2,3,4,5
            ggml_variants=android_armv8.6_1,android_armv8.2_2
            """.trimIndent()
        )
        assertEquals("arm64-v8a", snapshot.abi)
        assertTrue(snapshot.neon)
        assertTrue(snapshot.dotprod)
        assertFalse(snapshot.i8mm)
        assertEquals(listOf(6, 7), snapshot.bigCores)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), snapshot.littleCores)
        assertEquals(8, snapshot.logicalCpus)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateCoreAssignments() {
        NativeHardwareSnapshot.parse(
            """
            abi=arm64-v8a
            neon=true
            dotprod=false
            i8mm=false
            sve2=false
            avx2=false
            opencl_loader=false
            vulkan_loader=false
            logical_cpus=4
            big_cores=0,1
            little_cores=1,2,3
            ggml_variants=portable
            """.trimIndent()
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMalformedFeatureFlag() {
        NativeHardwareSnapshot.parse(
            """
            abi=arm64-v8a
            neon=maybe
            dotprod=false
            i8mm=false
            sve2=false
            avx2=false
            opencl_loader=false
            vulkan_loader=false
            logical_cpus=4
            big_cores=
            little_cores=
            ggml_variants=portable
            """.trimIndent()
        )
    }
}
