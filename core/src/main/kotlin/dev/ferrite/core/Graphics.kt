package dev.ferrite.core

enum class GraphicsApi(val label: String) { VULKAN_1_1("Vulkan 1.1"), VULKAN_1_0("Vulkan 1.0"), GLES_3_0("OpenGL ES 3.0"), GLES_2_0("OpenGL ES 2.0") }

/** What the phone reports (queried by the app: `PackageManager` Vulkan feature version, GL ES version). */
data class DeviceCaps(val vulkanApiVersion: Int, val glesVersion: Int, val driverBlocklist: Set<GraphicsApi> = emptySet())

data class ApiChoice(val api: GraphicsApi, val usable: Boolean, val reason: String)

object Graphics {
    private fun vk(major: Int, minor: Int) = (major shl 22) or (minor shl 12)

    /** Every API with whether this device can use it. Engines may support only a subset; pass what they support. */
    fun options(caps: DeviceCaps, engineSupports: Set<GraphicsApi>): List<ApiChoice> = GraphicsApi.entries.map { a ->
        val why = when {
            a !in engineSupports -> "not supported by this engine version"
            a in caps.driverBlocklist -> "known to fail on this driver"
            a == GraphicsApi.VULKAN_1_1 && caps.vulkanApiVersion < vk(1, 1) -> "device reports Vulkan < 1.1"
            a == GraphicsApi.VULKAN_1_0 && caps.vulkanApiVersion < vk(1, 0) -> "device has no Vulkan"
            a == GraphicsApi.GLES_3_0 && caps.glesVersion < 0x30000 -> "device reports OpenGL ES < 3.0"
            a == GraphicsApi.GLES_2_0 && caps.glesVersion < 0x20000 -> "device has no OpenGL ES 2.0"
            else -> null
        }
        ApiChoice(a, why == null, why ?: "ok")
    }

    /** First usable API in preference order, or null. */
    fun pick(caps: DeviceCaps, engineSupports: Set<GraphicsApi>): GraphicsApi? = options(caps, engineSupports).firstOrNull { it.usable }?.api
    fun vulkanVersion(major: Int, minor: Int) = vk(major, minor)
}
