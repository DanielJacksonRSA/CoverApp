package com.example.coverapp;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Entry screen for SerialTouch.
 *
 * <p>The app's actual work is done by {@link AccessibilityService}. This screen only provides a
 * shortcut that sends the user to the system Accessibility settings, where they can enable the
 * SerialTouch service. Once enabled, the service starts listening for a USB serial controller.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button openAccessibilitySettings = findViewById(R.id.btnAllowPermission);
        openAccessibilitySettings.setOnClickListener(
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }
}
