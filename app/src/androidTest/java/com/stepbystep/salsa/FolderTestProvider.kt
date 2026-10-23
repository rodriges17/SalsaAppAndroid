package com.stepbystep.salsa

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

/** Test APK owns this provider; returning its tree exercises real persisted Android grants. */
class FolderGrantActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri = DocumentsContract.buildTreeDocumentUri(FolderTestProvider.AUTHORITY, "root")
        setResult(RESULT_OK, Intent().setData(uri).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        ))
        finish()
    }
}

class FolderTestProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.stepbystep.salsa.test.documents"
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
    private val root: File get() = File(context!!.filesDir, "document-provider").canonicalFile.apply { mkdirs() }
    override fun onCreate() = true
    private fun file(id: String): File {
        require(id == "root" || id.startsWith("root/"))
        val value = File(root, id.removePrefix("root").trimStart('/')).canonicalFile
        require(value.path == root.path || value.path.startsWith(root.path + File.separator))
        return value
    }
    private fun id(file: File) = "root" + file.path.removePrefix(root.path)
    private fun row(cursor: MatrixCursor, file: File) {
        val values = mapOf<String, Any>(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to id(file),
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
            DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
            DocumentsContract.Document.COLUMN_FLAGS to if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE,
            DocumentsContract.Document.COLUMN_SIZE to file.length(),
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
        )
        cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply {
            val values = mapOf(DocumentsContract.Root.COLUMN_ROOT_ID to "root", DocumentsContract.Root.COLUMN_DOCUMENT_ID to "root", DocumentsContract.Root.COLUMN_TITLE to "SAF regression tests", DocumentsContract.Root.COLUMN_FLAGS to DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
            addRow(columnNames.map { values[it] }.toTypedArray())
        }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: COLUMNS).also { row(it, file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: COLUMNS).also { cursor -> file(parentDocumentId).listFiles().orEmpty().forEach { row(cursor, it) } }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && displayName != "." && displayName != "..")
        val created = File(file(parentDocumentId), displayName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) check(created.mkdir()) else check(created.createNewFile())
        return id(created)
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        file(documentId).path.startsWith(file(parentDocumentId).path + File.separator)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val target = file(documentId)
        if (mode.contains('w') && target.name.startsWith("denied-")) throw SecurityException("Schreibzugriff im Test entzogen")
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
    }
}
