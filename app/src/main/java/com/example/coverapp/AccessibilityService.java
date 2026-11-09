package com.example.coverapp;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Accessibility service that performs on-screen gestures on behalf of a physical USB controller.
 *
 * <p>On connection the service inflates a hidden overlay ({@code floating_bar}) that holds one
 * {@link Button} per supported action. The buttons are never shown to the user; they act as
 * programmatic click targets. {@link USBCommunication} listens to the serial port and calls
 * {@link Button#callOnClick()} on the relevant button when a trigger character arrives.
 */
public class AccessibilityService extends android.accessibilityservice.AccessibilityService {

    private static final String TAG = "SerialTouchService";

    /** Duration (ms) of a single tap gesture. */
    private static final int TAP_DURATION_MS = 100;
    /** Duration (ms) of a swipe gesture. */
    private static final int SWIPE_DURATION_MS = 500;
    /** Stroke timings (ms) used to build a double-tap gesture. */
    private static final int DOUBLE_TAP_FIRST_START_MS = 0;
    private static final int DOUBLE_TAP_STROKE_MS = 50;
    private static final int DOUBLE_TAP_SECOND_START_MS = 150;

    private FrameLayout overlayLayout;

    // These buttons are triggered from USBCommunication, so they are exposed statically.
    static Button homeButton;
    static Button swipeRightButton;
    static Button swipeLeftButton;
    static Button scrollDownButton;
    static Button scrollUpButton;
    static Button simulateTouchButton;
    static Button doubleTapButton;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No per-event handling is required; all actions are driven by the USB controller.
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_FOCUSED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        info.notificationTimeout = 100;
        setServiceInfo(info);

        addOverlay();
        bindButtons();

        Handler handler = new Handler(Looper.getMainLooper());
        USBCommunication.start(this, handler);
    }

    @Override
    public void onDestroy() {
        USBCommunication.stop();
        if (overlayLayout != null) {
            WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager != null) {
                windowManager.removeView(overlayLayout);
            }
            overlayLayout = null;
        }
        super.onDestroy();
    }

    /** Inflates the hidden overlay that hosts the programmatic click targets. */
    private void addOverlay() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayLayout = new FrameLayout(this);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.START;

        LayoutInflater.from(this).inflate(R.layout.floating_bar, overlayLayout);
        if (windowManager != null) {
            windowManager.addView(overlayLayout, layoutParams);
        }
    }

    /** Wires each overlay button to the gesture or global action it performs. */
    private void bindButtons() {
        Button powerButton = overlayLayout.findViewById(R.id.button1);
        powerButton.setOnClickListener(v -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG));

        Button volumeUpButton = overlayLayout.findViewById(R.id.button2);
        volumeUpButton.setOnClickListener(v -> adjustVolume());

        homeButton = overlayLayout.findViewById(R.id.btnHome);
        homeButton.setOnClickListener(v -> performGlobalAction(GLOBAL_ACTION_HOME));

        scrollDownButton = overlayLayout.findViewById(R.id.scrollDown);
        scrollDownButton.setOnClickListener(
                v -> scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));

        scrollUpButton = overlayLayout.findViewById(R.id.scrollUp);
        scrollUpButton.setOnClickListener(
                v -> scroll(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));

        swipeLeftButton = overlayLayout.findViewById(R.id.swipeLeft);
        swipeLeftButton.setOnClickListener(v -> swipe(1000, 100));

        swipeRightButton = overlayLayout.findViewById(R.id.swipeRight);
        swipeRightButton.setOnClickListener(v -> swipe(300, 1000));

        simulateTouchButton = overlayLayout.findViewById(R.id.simulateTouch);
        simulateTouchButton.setOnClickListener(v -> tapCenter());

        doubleTapButton = overlayLayout.findViewById(R.id.doubleTap);
        doubleTapButton.setOnClickListener(v -> doubleTapCenter());
    }

    private void adjustVolume() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void scroll(AccessibilityNodeInfo.AccessibilityAction action) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.d(TAG, "No active window to scroll");
            return;
        }
        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable != null) {
            scrollable.performAction(action.getId());
        }
    }

    /** Breadth-first search for the first node that supports forward scrolling. */
    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo root) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node == null) {
                continue;
            }
            if (node.getActionList()
                    .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                queue.addLast(node.getChild(i));
            }
        }
        return null;
    }

    private void swipe(int startX, int endX) {
        Path path = new Path();
        path.moveTo(startX, 1000);
        path.lineTo(endX, 1000);
        dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS))
                        .build(),
                null,
                null);
    }

    private void tapCenter() {
        Path path = centerPath();
        dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                        .build(),
                null,
                null);
    }

    private void doubleTapCenter() {
        Path path = centerPath();
        dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(
                                path, DOUBLE_TAP_FIRST_START_MS, DOUBLE_TAP_STROKE_MS))
                        .addStroke(new GestureDescription.StrokeDescription(
                                path, DOUBLE_TAP_SECOND_START_MS, DOUBLE_TAP_STROKE_MS))
                        .build(),
                null,
                null);
    }

    /** Builds a single-point path at the center of the display. */
    private Path centerPath() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager windowManager = getSystemService(WindowManager.class);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);

        Path path = new Path();
        path.moveTo(metrics.widthPixels / 2f, metrics.heightPixels / 2f);
        return path;
    }
}
