package com.ursafe.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Toast;

public final class ScreenConsentActivity extends Activity {
    private static final int REQUEST_CAPTURE = 8401;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            try {
                ScreenCaptureService.start(this, resultCode, data);
                Toast.makeText(this, "ეკრანის დაკვირვება ჩაირთო.", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                Toast.makeText(this, "Capture ვერ გაეშვა: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "ეკრანის გაზიარება არ ჩართულა.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
