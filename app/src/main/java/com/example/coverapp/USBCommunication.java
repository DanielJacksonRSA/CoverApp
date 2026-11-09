package com.example.coverapp;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages the USB-serial link to the physical controller and translates incoming bytes into
 * on-screen actions.
 *
 * <p>The controller (an Arduino-class board) sends single ASCII digits over serial. Each digit is
 * mapped to one of the overlay buttons owned by {@link AccessibilityService}; triggering that
 * button runs the corresponding gesture or global action.
 *
 * <p>Connection handling is resilient: a background scheduler repeatedly attempts to find and open
 * the device, and hotplug (attach/detach) broadcasts drive reconnection.
 *
 * <h2>Serial protocol</h2>
 *
 * <pre>
 *   '1' -> tap (center)      '5' -> scroll down
 *   '2' -> double tap        '6' -> swipe left
 *   '3' -> home              '7' -> swipe right
 *   '4' -> scroll up
 * </pre>
 */
public final class USBCommunication {

    private static final String TAG = "SerialTouchUsb";

    private static final String ACTION_USB_PERMISSION = "com.example.coverapp.USB_PERMISSION";

    /** Delay between reconnection attempts. */
    private static final long RECONNECTION_DELAY_MS = 3000;
    /** Vendor ID of the controller board. */
    private static final int CONTROLLER_VENDOR_ID = 5824;
    private static final int BAUD_RATE = 2400;
    private static final int DATA_BITS = 8;
    private static final int READ_TIMEOUT_MS = 1000;
    private static final int READ_BUFFER_SIZE = 64;
    /** Delay before opening a device once permission is granted, to let the system settle. */
    private static final long OPEN_AFTER_PERMISSION_DELAY_MS = 500;

    private static UsbManager usbManager;
    private static UsbSerialPort usbSerialPort;
    private static Context appContext;

    private static Thread readingThread;
    private static ScheduledExecutorService scheduler;
    private static volatile boolean autoReconnectEnabled = false;

    private USBCommunication() {
        // Utility class; not instantiable.
    }

    /** Starts USB discovery and the auto-reconnect loop. */
    public static void start(Context context, Handler handler) {
        appContext = context.getApplicationContext();
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        startAutoReconnect();
    }

    /** Stops listening, cancels reconnection, and unregisters the broadcast receiver. */
    public static void stop() {
        stopAutoReconnect();
        if (appContext != null) {
            try {
                appContext.unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Receiver already unregistered");
            }
        }
    }

    private static void startAutoReconnect() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        autoReconnectEnabled = true;

        scheduler.scheduleWithFixedDelay(
                () -> {
                    if (autoReconnectEnabled && (usbSerialPort == null || !usbSerialPort.isOpen())) {
                        Log.d(TAG, "Scheduled check: looking for controller");
                        findDeviceAndConnect();
                    }
                },
                0,
                RECONNECTION_DELAY_MS,
                TimeUnit.MILLISECONDS);
    }

    private static void stopAutoReconnect() {
        autoReconnectEnabled = false;
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        stopListening();
    }

    private static void findDeviceAndConnect() {
        if (usbManager == null || appContext == null) {
            Log.w(TAG, "UsbManager or context unavailable; cannot search for device");
            return;
        }

        UsbDevice controller = findController();
        if (controller == null) {
            Log.d(TAG, "No matching controller found");
            return;
        }

        if (usbManager.hasPermission(controller)) {
            openDevice(controller);
        } else {
            Log.d(TAG, "Requesting USB permission for " + controller.getDeviceName());
            PendingIntent permissionIntent =
                    PendingIntent.getBroadcast(
                            appContext,
                            0,
                            new Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(controller, permissionIntent);
        }
    }

    private static UsbDevice findController() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (device.getVendorId() == CONTROLLER_VENDOR_ID) {
                return device;
            }
        }
        return null;
    }

    private static final BroadcastReceiver usbReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action == null) {
                        return;
                    }
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    switch (action) {
                        case ACTION_USB_PERMISSION:
                            handlePermissionResult(intent, device);
                            break;
                        case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                            if (isController(device)) {
                                Log.d(TAG, "Controller attached");
                                findDeviceAndConnect();
                            }
                            break;
                        case UsbManager.ACTION_USB_DEVICE_DETACHED:
                            if (isController(device)) {
                                Log.w(TAG, "Controller detached");
                                stopListening();
                            }
                            break;
                        default:
                            break;
                    }
                }
            };

    private static void handlePermissionResult(Intent intent, UsbDevice device) {
        if (device == null) {
            Log.w(TAG, "USB permission broadcast with null device");
            return;
        }
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            Log.d(TAG, "USB permission granted; opening device");
            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> openDevice(device), OPEN_AFTER_PERMISSION_DELAY_MS);
        } else {
            Log.w(TAG, "USB permission denied for " + device.getDeviceName());
        }
    }

    private static boolean isController(UsbDevice device) {
        return device != null && device.getVendorId() == CONTROLLER_VENDOR_ID;
    }

    private static void openDevice(UsbDevice device) {
        if (usbSerialPort != null
                && usbSerialPort.isOpen()
                && usbSerialPort.getDriver().getDevice().equals(device)) {
            Log.d(TAG, "Port already open for this device");
            return;
        }

        stopListening();

        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                Log.e(TAG, "Failed to open connection (device busy or permission revoked)");
                return;
            }

            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null) {
                Log.e(TAG, "No serial driver for device " + device.getDeviceName());
                connection.close();
                return;
            }

            List<UsbSerialPort> ports = driver.getPorts();
            if (ports.isEmpty()) {
                Log.e(TAG, "No serial ports on device " + device.getDeviceName());
                connection.close();
                return;
            }

            usbSerialPort = ports.get(0);
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(
                    BAUD_RATE, DATA_BITS, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbSerialPort.setDTR(true);
            usbSerialPort.setRTS(true);

            startListening();
            Log.d(TAG, "Controller connected: " + device.getDeviceName());
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Error opening device " + device.getDeviceName(), e);
            closeQuietly(connection);
            usbSerialPort = null;
        }
    }

    private static void startListening() {
        stopReadingThread();

        readingThread =
                new Thread(
                        () -> {
                            byte[] buffer = new byte[READ_BUFFER_SIZE];
                            Log.d(TAG, "Listening started");

                            if (usbSerialPort == null || !usbSerialPort.isOpen()) {
                                Log.e(TAG, "Port not open; listening thread terminating");
                                return;
                            }

                            while (!Thread.interrupted()) {
                                try {
                                    int numBytes = usbSerialPort.read(buffer, READ_TIMEOUT_MS);
                                    if (numBytes > 0) {
                                        String data = new String(buffer, 0, numBytes).trim();
                                        for (int i = 0; i < data.length(); i++) {
                                            dispatchTrigger(data.charAt(i));
                                        }
                                    }
                                } catch (IOException e) {
                                    Log.e(TAG, "Read error; stopping listener", e);
                                    stopListening();
                                    break;
                                } catch (Exception e) {
                                    Log.e(TAG, "Unexpected error in listener", e);
                                    stopListening();
                                    break;
                                }
                            }
                            Log.d(TAG, "Listening stopped");
                        });
        readingThread.start();
    }

    /** Maps a trigger character from the controller to the matching overlay button. */
    private static void dispatchTrigger(char trigger) {
        Button target;
        switch (trigger) {
            case '1':
                target = AccessibilityService.simulateTouchButton;
                break;
            case '2':
                target = AccessibilityService.doubleTapButton;
                break;
            case '3':
                target = AccessibilityService.homeButton;
                break;
            case '4':
                target = AccessibilityService.scrollUpButton;
                break;
            case '5':
                target = AccessibilityService.scrollDownButton;
                break;
            case '6':
                target = AccessibilityService.swipeLeftButton;
                break;
            case '7':
                target = AccessibilityService.swipeRightButton;
                break;
            default:
                return;
        }
        if (target != null) {
            Log.d(TAG, "Trigger '" + trigger + "'");
            target.post(target::callOnClick);
        }
    }

    private static void stopListening() {
        stopReadingThread();
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing port", e);
            } finally {
                usbSerialPort = null;
            }
        }
    }

    private static void stopReadingThread() {
        if (readingThread != null) {
            readingThread.interrupt();
            try {
                readingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readingThread = null;
        }
    }

    private static void closeQuietly(UsbDeviceConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing connection", e);
            }
        }
    }
}
