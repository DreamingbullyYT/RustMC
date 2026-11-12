package dev.horizon.mods

import android.content.Context
import android.net.Uri
import dev.horizon.instances.InstanceRepository
import java.io.File
import java.util.Locale

data class ShaderPack(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long
)

/**
 * HShader file manager. Imports ZIP shader packs into the selected instance.
 * It does not claim a pack is runtime-compatible until the engine shader loader exists.
 */
class HShaderRepository(context: Context) {
    private val appContext = context.applicationContext
    private val instances = InstanceRepository(appContext)

    fun list(): List<ShaderPack> {
        val directory = shaderDirectory() ?: return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            ?.map { ShaderPack(it.name, it.nameWithoutExtension, it.length()) }
            ?.sortedBy { it.displayName.lowercase(Locale.ROOT) }
            ?: emptyList()
    }

    fun importZip(uri: Uri): ShaderPack {
        val instance = instances.getActiveInstance()
            ?: throw IllegalStateException("Create and select an instance first.")
        val directory = File(instance.directoryPath, "shaderpacks")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not access this instance's shaderpacks folder.")
        }

        val suggestedName = appContext.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "shaderpack.zip"
        val safeName = suggestedName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim().take(100).ifBlank { "shaderpack.zip" }
        val finalName = if (safeName.endsWith(".zip", true)) safeName else "$safeName.zip"
        val destination = File(directory, finalName)
        require(!destination.exists()) { "A shader pack with this filename is already imported." }

        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open the selected file.")
        input.use { source ->
            val buffered = source.buffered()
            buffered.mark(4)
            val signature = ByteArray(4)
            val count = buffered.read(signature)
            buffered.reset()
            require(count >= 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte()) {
                "The selected file is not a ZIP archive."
            }
            try {
                destination.outputStream().buffered().use { output -> buffered.copyTo(output) }
            } catch (failure: Throwable) {
                destination.delete()
                throw failure
            }
        }
        return ShaderPack(destination.name, destination.nameWithoutExtension, destination.length())
    }

    fun delete(fileName: String) {
        require(fileName.endsWith(".zip", true) && fileName.none { it == '/' || it == '\\' }) {
            "Invalid shader pack filename."
        }
        val directory = shaderDirectory() ?: throw IllegalStateException("No active instance.")
        val target = File(directory, fileName)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "Invalid shader pack path." }
        if (target.exists() && !target.delete()) throw IllegalStateException("Could not delete shader pack.")
    }

    private fun shaderDirectory(): File? {
        val instance = instances.getActiveInstance() ?: return null
        return File(instance.directoryPath, "shaderpacks").also { if (!it.exists()) it.mkdirs() }
    }
}
