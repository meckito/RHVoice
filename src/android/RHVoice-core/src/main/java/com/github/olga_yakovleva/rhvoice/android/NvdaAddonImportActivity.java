package com.github.olga_yakovleva.rhvoice.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public final class NvdaAddonImportActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    private void handleIntent(Intent intent) {
        if (intent == null)
            return;
        Uri uri = intent.getData();
        if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) {
            Toast.makeText(this, R.string.import_addon_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (SecurityException ignored) {
        }
        Intent svc = new Intent(this, NvdaAddonImportService.class);
        svc.setAction(NvdaAddonImportService.ACTION_IMPORT_ADDON);
        svc.setData(uri);
        svc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startService(svc);
        Toast.makeText(this, R.string.import_addon_started, Toast.LENGTH_SHORT).show();
    }
}
