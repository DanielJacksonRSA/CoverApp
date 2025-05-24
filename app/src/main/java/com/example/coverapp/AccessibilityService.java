package com.example.coverapp;


import static com.example.coverapp.MainActivity.appcontext;

import android.accessibilityservice.AccessibilityServiceInfo;

import android.accessibilityservice.GestureDescription;

import android.content.pm.ApplicationInfo;

import android.content.pm.PackageManager;

import android.content.res.Resources;

import android.graphics.Path;

import android.graphics.PixelFormat;

import android.media.AudioManager;

import android.os.Handler;

import android.os.Looper;

import android.util.Log;

import android.view.Gravity;

import android.view.LayoutInflater;

import android.view.View;

import android.view.WindowManager;

import android.view.accessibility.AccessibilityEvent;

import android.view.accessibility.AccessibilityNodeInfo;

import android.widget.Button;

import android.widget.FrameLayout;
import android.widget.Toast;


import java.util.ArrayDeque;

import java.util.Deque;


public class AccessibilityService extends android.accessibilityservice.AccessibilityService {


    private static final String TAG = "AccessibilityServiceExample";
    FrameLayout mLayout;
    Button swipeButton;
    public static Button power;


    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Log.e(TAG, "onAccessibilityEvent: ");
        String packageName = event.getPackageName().toString();
        PackageManager packageManager = this.getPackageManager();
        try {
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence applicationLabel = packageManager.getApplicationLabel(applicationInfo);
            Log.e(TAG, "app name is: " + applicationLabel);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

    }


    @Override
    public void onInterrupt() {
        Log.e(TAG, "Something went wrong");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        info.notificationTimeout = 100;
        this.setServiceInfo(info);

        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mLayout = new FrameLayout(this);
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.LEFT;
        LayoutInflater inflater = LayoutInflater.from(this);
        inflater.inflate(R.layout.floating_bar, mLayout);
        windowManager.addView(mLayout, layoutParams);
        Toast.makeText(this, "Context works in Access", Toast.LENGTH_SHORT).show();
        configurePowerButton();
        configureSimulateTouch();
        configureVolumeButton();
        configureScrollButton();
        configureSwipeButton();
        configureSwipeRightButton();
        configureScrollUpButton();
        configure2SimulateTouch();
        Handler handler = new Handler(Looper.getMainLooper());
        USBCommunication usbCommunication = new USBCommunication(this, handler);
    }

    private void configurePowerButton() {
        power = mLayout.findViewById(R.id.button1);
        power.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                powerButton();
            }
        });

    }

    public void powerButton() {
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);

    }


    private void configureVolumeButton() {
        Button volumeUpButton = mLayout.findViewById(R.id.button2);
        volumeUpButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View view) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            }
        });
    }


    private void configureSimulateTouch() {
        Button btnSimulateTouch = mLayout.findViewById(R.id.simulateTouch);
        btnSimulateTouch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.e(TAG, "onClick: Simulate Touch");
                Path tap = new Path();
                tap.moveTo(500, 1000);
                GestureDescription.Builder tapBuilder = new GestureDescription.Builder();
                tapBuilder.addStroke(new GestureDescription.StrokeDescription(tap, 0, 500));
                dispatchGesture(tapBuilder.build(), null, null);
            }
        });
    }

    private void configure2SimulateTouch(){
        Button btnSimulateTouch = mLayout.findViewById(R.id.simulateTouch);
        btnSimulateTouch.setOnClickListener(new View.OnClickListener() {
            private long lastTapTime = 0;
            private static final long DOUBLE_TAP_TIMEOUT = 300;
            @Override
            public void onClick(View v) {
                long currentTime = System.currentTimeMillis();
                // Get screen dimensions
                int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
                int screenHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
                // Calculate center of the screen
                float centerX = screenWidth / 2f;
                float centerY = screenHeight / 2f;
                if (currentTime - lastTapTime <= DOUBLE_TAP_TIMEOUT) {
                    Log.e(TAG, "onClick: Double Tap");
                    // Create the path to the center of the screen for both taps
                    Path tap = new Path();
                    tap.moveTo(centerX, centerY); // First tap at the center
                    GestureDescription.Builder tapBuilder = new GestureDescription.Builder();
                    tapBuilder.addStroke(new GestureDescription.StrokeDescription(tap, 0, 100)); // First stroke
                    // Second tap at the center
                    tap.moveTo(centerX, centerY);
                    tapBuilder.addStroke(new GestureDescription.StrokeDescription(tap, 200, 100)); // Second stroke
                    dispatchGesture(tapBuilder.build(), null, null);
                } else {
                    lastTapTime = currentTime;
                    Log.e(TAG, "onClick: Single Tap detected, waiting for second tap");
                }
            }
        });
    }


    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> deque = new ArrayDeque<>();
        deque.add(root);
        while (!deque.isEmpty()) {
            AccessibilityNodeInfo node = deque.removeFirst();
            if (node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                deque.addLast(node.getChild(i));
            }
        }
        return null;
    }


    private void configureScrollButton() {
        Button scrollButton = mLayout.findViewById(R.id.scrollDown);
        scrollButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AccessibilityNodeInfo scrollable = findScrollableNode(getRootInActiveWindow());
                if (scrollable != null) {
                    scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId());
                }
            }
        });

    }


    private void configureScrollUpButton() {
        Button scrollButton = mLayout.findViewById(R.id.scrollUp);
        scrollButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                AccessibilityNodeInfo scrollable = findScrollableNode(getRootInActiveWindow());
                if (scrollable != null) {
                    scrollable.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.getId());
                }
            }
        });
    }


    private void configureSwipeButton() {

        Button swipeButton = mLayout.findViewById(R.id.swipeLeft);

        swipeButton.setOnClickListener(new View.OnClickListener() {

            @Override

            public void onClick(View view) {
                Path swipePath = new Path();
                swipePath.moveTo(1000, 1000);
                swipePath.lineTo(100, 1000);
                GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
                gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 500));
                dispatchGesture(gestureBuilder.build(), null, null);

            }

        });

    }




    private void configureSwipeRightButton() {

        swipeButton = mLayout.findViewById(R.id.swipeRight);

        swipeButton.setOnClickListener(new View.OnClickListener() {

            @Override

            public void onClick(View view) {
                Path swipePath = new Path();
                swipePath.moveTo(100, 1000);
                swipePath.lineTo(1000, 1000);
                GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
                gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 500));
                dispatchGesture(gestureBuilder.build(), null, null);
            }

        });

    }


    private void triggerSwipeRight() {


    }

}