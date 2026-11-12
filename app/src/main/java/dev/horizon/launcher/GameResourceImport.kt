package dev.horizon.launcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.horizon.launcher.core.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Imports Mojang launcher files into the installer's cache; native code verifies every SHA-1. */
internal suspend fun importMinecraftFolder(
    context: Context,
    tree: Uri,
    rootDocumentId: String = DocumentsContract.getTreeDocumentId(tree),
): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val rootId = rootDocumentId

    fun children(parentId: String): List<Pair<String, String>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = mutableListOf<Pair<String, String>>()
        resolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) result += cursor.getString(nameCol) to cursor.getString(idCol)
        }
        return result
    }
    fun child(parentId: String, name: String): String? = children(parentId).firstOrNull { it.first == name }?.second
    fun locate(vararg parts: String): String? {
        var id = rootId
        for (part in parts) id = child(id, part) ?: return null
        return id
    }
    fun copyToCache(relative: String, documentId: String): Boolean {
        val staging = File(context.cacheDir, "minecraft-import/$relative")
        staging.parentFile?.mkdirs()
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openInputStream(uri)?.use { input -> staging.outputStream().use { output -> input.copyTo(output) } } ?: return false
        try { Native.nativeImportCachedFile(relative, staging.absolutePath) } finally { staging.delete() }
        return true
    }

    val versionJsonId = locate("versions", "26.3", "26.3.json")
        ?: error("Could not find versions/26.3/26.3.json. Choose the Minecraft game folder.")
    val versionJson = File(context.cacheDir, "minecraft-import/version.json")
    versionJson.parentFile?.mkdirs()
    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, versionJsonId))!!
        .use { input -> versionJson.outputStream().use(input::copyTo) }
    val metadata = JSONObject(versionJson.readText())
    versionJson.delete()
    val assetIndexId = metadata.getJSONObject("assetIndex").getString("id")
    var imported = 0
    val jarId = locate("versions", "26.3", "26.3.jar")
    if (jarId != null && copyToCache("client.jar", jarId)) imported++
    val indexId = locate("assets", "indexes", "$assetIndexId.json")
        ?: error("Could not find assets/indexes/$assetIndexId.json in the selected folder.")
    if (!copyToCache("asset-index.json", indexId)) error("Could not read the Minecraft asset index.")
    imported++

    val indexDocument = File(context.cacheDir, "minecraft-import/asset-index.json")
    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, indexId))!!
        .use { input -> indexDocument.outputStream().use(input::copyTo) }
    val objects = JSONObject(indexDocument.readText()).getJSONObject("objects")
    indexDocument.delete()
    val assetsId = locate("assets", "objects")
    if (assetsId != null) {
        val needed = objects.keys().asSequence()
            .map { objects.getJSONObject(it).getString("hash") }
            .filter { it.length == 40 }
            .toSet()
        for ((prefix, prefixId) in children(assetsId)) {
            if (prefix.length != 2) continue
            val requested = needed.filter { it.startsWith(prefix) }.toSet()
            if (requested.isEmpty()) continue
            for ((hash, documentId) in children(prefixId)) {
                if (hash in requested && copyToCache("objects/$hash", documentId)) imported++
            }
        }
    }
    imported
}
