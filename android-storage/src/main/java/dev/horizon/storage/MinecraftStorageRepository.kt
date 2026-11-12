package dev.horizon.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import android.provider.DocumentsContract.Document
import android.content.ContentResolver

data class StorageEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

class MinecraftStorageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("minecraft_storage", Context.MODE_PRIVATE)

    val selectedTreeUri: Uri?
        get() = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun selectTree(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun rootDocumentId(): String? {
        val tree = selectedTreeUri ?: return null
        return DocumentsContract.getTreeDocumentId(tree)
    }

    fun list(parentDocumentId: String): List<StorageEntry> {
        val tree = selectedTreeUri ?: return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
        val entries = mutableListOf<StorageEntry>()
        val cursor: Cursor? = appContext.contentResolver.query(childrenUri, projection, null, null, null)
        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val modifiedColumn = it.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val mime = it.getString(mimeColumn)
                entries += StorageEntry(
                    documentId = it.getString(idColumn),
                    name = it.getString(nameColumn) ?: "Unnamed",
                    isDirectory = mime == Document.MIME_TYPE_DIR,
                    sizeBytes = if (it.isNull(sizeColumn)) 0L else it.getLong(sizeColumn),
                    lastModified = if (it.isNull(modifiedColumn)) 0L else it.getLong(modifiedColumn)
                )
            }
        }
        return entries.sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createDirectory(parentDocumentId: String, name: String): Boolean {
        val tree = selectedTreeUri ?: return false
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocumentId)
        return DocumentsContract.createDocument(
            appContext.contentResolver,
            parent,
            Document.MIME_TYPE_DIR,
            name.trim()
        ) != null
    }

    fun rename(documentId: String, newName: String): Boolean {
        val tree = selectedTreeUri ?: return false
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return DocumentsContract.renameDocument(appContext.contentResolver, document, newName.trim()) != null
    }

    fun delete(documentId: String): Boolean {
        val tree = selectedTreeUri ?: return false
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return DocumentsContract.deleteDocument(appContext.contentResolver, document)
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
    }
}
