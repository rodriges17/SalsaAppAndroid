package com.stepbystep.salsa;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Framework-only provider in the test APK; production APK contains neither test component. */
public class FolderTestProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.stepbystep.salsa.test.documents";
    private static final String[] COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
    };
    @Override public boolean onCreate() { return true; }
    private File canonical(File file) {
        try { return file.getCanonicalFile(); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private File root() {
        File directory = canonical(new File(getContext().getFilesDir(), "document-provider"));
        directory.mkdirs();
        return directory;
    }
    private File file(String id) {
        if (!id.equals("root") && !id.startsWith("root/")) throw new IllegalArgumentException("Invalid document ID");
        File directory = root();
        File result = canonical(new File(directory, id.equals("root") ? "" : id.substring(5)));
        if (!result.equals(directory) && !result.getPath().startsWith(directory.getPath() + File.separator))
            throw new IllegalArgumentException("Invalid document path");
        return result;
    }
    private String id(File file) { return "root" + file.getPath().substring(root().getPath().length()); }
    private void row(MatrixCursor cursor, File file) {
        String[] columns = cursor.getColumnNames();
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Document.COLUMN_DOCUMENT_ID: values[i] = id(file); break;
                case Document.COLUMN_DISPLAY_NAME: values[i] = file.getName(); break;
                case Document.COLUMN_MIME_TYPE: values[i] = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                case Document.COLUMN_FLAGS: values[i] = file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE; break;
                case Document.COLUMN_SIZE: values[i] = file.length(); break;
                case Document.COLUMN_LAST_MODIFIED: values[i] = file.lastModified(); break;
            }
        }
        cursor.addRow(values);
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            switch (columns[i]) {
                case Root.COLUMN_ROOT_ID: case Root.COLUMN_DOCUMENT_ID: values[i] = "root"; break;
                case Root.COLUMN_TITLE: values[i] = "SAF regression tests"; break;
                case Root.COLUMN_FLAGS: values[i] = Root.FLAG_SUPPORTS_CREATE; break;
            }
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        row(cursor, file(documentId));
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : COLUMNS);
        File[] children = file(parentDocumentId).listFiles();
        if (children != null) for (File child : children) row(cursor, child);
        return cursor;
    }
    @Override public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        if (displayName.contains("/") || displayName.equals(".") || displayName.equals("..")) throw new IllegalArgumentException("Invalid name");
        File created = new File(file(parentDocumentId), displayName);
        try {
            boolean success = mimeType.equals(Document.MIME_TYPE_DIR) ? created.mkdir() : created.createNewFile();
            if (!success) throw new FileNotFoundException("Could not create document");
        } catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return id(created);
    }
    @Override public void deleteDocument(String documentId) throws FileNotFoundException {
        if (!file(documentId).delete()) throw new FileNotFoundException("Could not remove document");
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return file(documentId).getPath().startsWith(file(parentDocumentId).getPath() + File.separator);
    }
    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File target = file(documentId);
        if (mode.contains("w") && target.getName().startsWith("denied-")) throw new SecurityException("Schreibzugriff im Test entzogen");
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }
}
