package com.stepbystep.salsa;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Runs in the test APK process, using framework classes only. */
public class FolderGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent()
            .setData(DocumentsContract.buildTreeDocumentUri(FolderTestProvider.AUTHORITY, "root"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResult(RESULT_OK, result);
        finish();
    }
}
