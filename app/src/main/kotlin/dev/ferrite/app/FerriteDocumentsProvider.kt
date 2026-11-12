package dev.ferrite.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import dev.ferrite.core.SafePaths
import java.io.File
import java.io.FileNotFoundException

/**
 * Shows the instances folder in the system Files app (backups, mods, worlds, logs), with no network or root.
 * All path rules live in [SafePaths]; this class only adapts them to the Storage Access Framework.
 */
class FerriteDocumentsProvider : DocumentsProvider() {
    private val base: File by lazy { File(context!!.filesDir, "instances").apply { mkdirs() } }

    private fun file(id: String): File = try { SafePaths.resolve(base, id) } catch (e: IllegalArgumentException) { throw FileNotFoundException(e.message) }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_ICON)).apply {
        newRow().add(Root.COLUMN_ROOT_ID, SafePaths.ROOT_ID).add(Root.COLUMN_DOCUMENT_ID, SafePaths.ROOT_ID)
            .add(Root.COLUMN_TITLE, "Ferrite").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, context!!.applicationInfo.icon)
    }

    private fun row(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        val flags = if (f == base.canonicalFile) Document.FLAG_DIR_SUPPORTS_CREATE
        else if (dir) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val mime = if (dir) Document.MIME_TYPE_DIR else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, SafePaths.idOf(base, f)).add(Document.COLUMN_DISPLAY_NAME, if (f == base.canonicalFile) "Ferrite" else f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_FLAGS, flags).add(Document.COLUMN_SIZE, if (dir) null else f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
    }

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: columns).also { c -> file(documentId).also { if (!it.exists()) throw FileNotFoundException(documentId) }.let { row(c, it) } }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = file(parentDocumentId); if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: columns).also { c -> dir.listFiles()?.sortedBy { it.name }?.forEach { row(c, it) } }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = SafePaths.isChild(base, parentDocumentId, documentId)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId); if (!f.isFile) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = file(parentDocumentId); if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        if (!SafePaths.validName(displayName)) throw FileNotFoundException("bad name")
        var target = File(dir, displayName); var n = 1
        while (target.exists()) target = File(dir, "${target.nameWithoutExtension.removeSuffix(" ($n)")} (${n++})" + (if (target.extension.isEmpty()) "" else "." + target.extension))
        val ok = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!ok) throw FileNotFoundException("could not create $displayName")
        return SafePaths.idOf(base, target)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId); if (f == base.canonicalFile) throw FileNotFoundException("cannot delete the root")
        if (!f.deleteRecursively()) throw FileNotFoundException("could not delete")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId); if (f == base.canonicalFile || !SafePaths.validName(displayName)) throw FileNotFoundException("bad rename")
        val t = File(f.parentFile, displayName); if (t.exists() || !f.renameTo(t)) throw FileNotFoundException("could not rename")
        return SafePaths.idOf(base, t)
    }
}
