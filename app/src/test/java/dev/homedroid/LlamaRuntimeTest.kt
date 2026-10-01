package dev.homedroid

import org.junit.Assert.assertEquals
import org.junit.Test

class LlamaRuntimeTest {
    @Test fun devicesAreReadFromListDevicesOutput() {
        val out = """
            load_backend: loaded Vulkan backend from /data/user/0/dev.homedroid/files/ai/runtime/libggml-vulkan.so
            load_backend: loaded CPU backend from /data/user/0/dev.homedroid/files/ai/runtime/libggml-cpu-android_armv8.6_1.so
            Available devices:
              Vulkan0: Samsung Xclipse 940 (11010 MiB, 9000 MiB free)
              GPUOpenCL: QUALCOMM Adreno(TM) 750 (7365 MiB, 7365 MiB free)
        """.trimIndent()
        assertEquals(listOf("Vulkan0" to "Samsung Xclipse 940", "GPUOpenCL" to "QUALCOMM Adreno(TM) 750"), LlamaRuntime.parseDevices(out))
        assertEquals(emptyList<Pair<String, String>>(), LlamaRuntime.parseDevices("Available devices:\n"))
    }
}
