package com.example.coverapp;

import static com.example.coverapp.AccessibilityService.btnDoubleTap;
import static com.example.coverapp.AccessibilityService.btnSimulateTouch;
import static com.example.coverapp.AccessibilityService.home;
import static com.example.coverapp.AccessibilityService.power;
import static com.example.coverapp.AccessibilityService.scrollButton;
import static com.example.coverapp.AccessibilityService.scrollUpButton;
import static com.example.coverapp.AccessibilityService.swipeLeftButton;
import static com.example.coverapp.AccessibilityService.swipeRightButton;

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
import android.widget.Toast;

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

public class USBCommunication {
    private static final String ACTION_USB_PERMISSION = "com.example.coverapp.USB_PERMISSION";
    private static final long RECONNECTION_DELAY_MS = 3000; // 3 seconds delay for reconnection attempts
    private static final int ARDUINO_VENDOR_ID = 9025;
    private static final int BAUD_RATE = 2400;
    private static UsbManager usbManager;
    private static UsbSerialPort usbSerialPort;
    private static Context thiscontext;
    private static Handler uiHandler; // Handler for UI operations, if needed

    private static Thread readingThread;
    private static ScheduledExecutorService scheduler; // For periodic checks

    // Flag to control the automatic reconnection logic
    private static volatile boolean autoReconnectEnabled = false;

    public static void USBRun(Context context, Handler handler) {
        thiscontext = context;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        uiHandler = handler; // Assuming this is a Handler for the UI thread

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        // Start the initial device search and enable auto-reconnect
        startAutoReconnect();
    }

    private static void startAutoReconnect() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow(); // Stop any existing scheduler
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        autoReconnectEnabled = true;

        // Schedule a task to continuously check for and connect to the USB device
        scheduler.scheduleWithFixedDelay(() -> {
            if (autoReconnectEnabled && (usbSerialPort == null || !usbSerialPort.isOpen())) {
                Log.d("USBCommunication", "Scheduled check: Attempting to find and connect to USB device.");
                findUsbDeviceAndConnect();
            }
        }, 0, RECONNECTION_DELAY_MS, TimeUnit.MILLISECONDS); // Initial delay 0, then fixed 3s delay
    }

    public static void stopAutoReconnect() {
        autoReconnectEnabled = false;
        if (scheduler != null) {
            scheduler.shutdown();
            Log.d("USBCommunication", "Auto-reconnect scheduler shut down.");
        }
        stopListeningInternal(); // Ensure any active listening is also stopped
    }


    private static void findUsbDeviceAndConnect() {
        if (usbManager == null || thiscontext == null) {
            Log.e("USBCommunication", "USBManager or Context is null, cannot find device.");
            return;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        UsbDevice arduinoDevice = null;

        for (UsbDevice device : deviceList.values()) {
            if (device.getVendorId() == ARDUINO_VENDOR_ID) { // Arduino Vendor ID
                arduinoDevice = device;
                break;
            }
        }

        if (arduinoDevice != null) {
            if (usbManager.hasPermission(arduinoDevice)) {
                Log.d("USB Permission", "Already granted for device: " + arduinoDevice.getDeviceName());
                openUsbDevice(arduinoDevice);
            } else {
                Log.d("USB Permission", "Requesting permission for device: " + arduinoDevice.getDeviceName());
                PendingIntent permissionIntent = PendingIntent.getBroadcast(
                        thiscontext,
                        0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                );
                usbManager.requestPermission(arduinoDevice, permissionIntent);
                // Permission request is asynchronous, the receiver will handle connection if granted.
            }
        } else {
            Log.d("Find USB Device", "No suitable Arduino device found.");
        }
    }


    private static final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.d("USB Permission", "Permission granted for device: " + device.getDeviceName() + ". Attempting to open.");
                        // It's crucial to delay slightly to allow the system to fully set up the device after permission
                        new Handler(Looper.getMainLooper()).postDelayed(() -> openUsbDevice(device), 500);
                    } else {
                        Log.w("USB Permission", "Permission denied for device: " + device.getDeviceName());
                    }
                } else {
                    Log.w("USB Permission", "USB_PERMISSION broadcast received with null device.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice attachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (attachedDevice != null && attachedDevice.getVendorId() == ARDUINO_VENDOR_ID) {
                    Log.d("USB Hotplug", "Arduino device attached: " + attachedDevice.getDeviceName());
                    // Trigger an immediate check, the scheduler will pick it up or this might connect it.
                    findUsbDeviceAndConnect();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (detachedDevice != null && detachedDevice.getVendorId() == ARDUINO_VENDOR_ID) {
                    Log.w("USB Hotplug", "Arduino device detached: " + detachedDevice.getDeviceName());
                    // Device detached, clean up and let the scheduler attempt reconnection
                    stopListeningInternal(); // Stop the current reading thread and close port
                    // The scheduler will automatically attempt to find and reconnect.
                }
            }
        }
    };

    private static void listenForData() {
        // Stop any existing reading thread cleanly before starting a new one
        if (readingThread != null) {
            stopListeningInternal();
        }

        readingThread = new Thread(() -> {
            byte[] buffer = new byte[64];
            Log.d("Listening Status: ", "started");

            if (usbSerialPort == null || !usbSerialPort.isOpen()) {
                Log.e("USBCommunication", "usbSerialPort is not open or null, cannot start listening. Terminating reading thread.");
                return;
            }

            while (!Thread.interrupted()) {
                try {
                    int numBytes = usbSerialPort.read(buffer, 1000);
                    if (numBytes > 0) {
                        String receivedData = new String(buffer, 0, numBytes).trim();
                        Log.d("Received Data", "Parsed: '" + receivedData + "'");

                        if (receivedData.equals("SWIPE_RIGHT")) {
                            Log.d("Trigger:", "SWIPE_RIGHT");
                            if (swipeRightButton != null) swipeRightButton.callOnClick();
                        } else if (receivedData.equals("SWIPE_LEFT")) {
                            Log.d("Trigger:", "SWIPE_LEFT");
                            if (swipeLeftButton != null) swipeLeftButton.callOnClick();
                        } else if (receivedData.equals("SWIPE_UP")) {
                            Log.d("Trigger:", "SWIPE_UP");
                            if (scrollUpButton != null) scrollUpButton.callOnClick();
                        } else if (receivedData.equals("SWIPE_DOWN")) {
                            Log.d("Trigger:", "SWIPE_DOWN");
                            if (scrollButton != null) scrollButton.callOnClick();
                        } else if (receivedData.equals("BUTTON1")) {
                            Log.d("Trigger:", "BUTTON1");
                            if (btnSimulateTouch != null) btnSimulateTouch.callOnClick();
                        } else if (receivedData.equals("BUTTON2")) {
                            Log.d("Trigger:", "BUTTON2");
                            if (btnDoubleTap != null) btnDoubleTap.callOnClick();
                        } else if (receivedData.equals("BUTTON3")) {
                            Log.d("Trigger:", "BUTTON3");
                            if (home != null) home.callOnClick();
                        }
                    }
                } catch (IOException e) {
                    Log.e("USB Error", "Error reading data from USB port: " + e.getMessage(), e);
                    // Disconnection detected. Clean up and let the scheduler handle reconnection.
                    stopListeningInternal();
                    break; // Exit the reading loop immediately
                } catch (NullPointerException npe) {
                    Log.e("USB Error", "NullPointerException in reading thread (usbSerialPort might be null): " + npe.getMessage(), npe);
                    stopListeningInternal();
                    break;
                } catch (Exception e) {
                    Log.e("USBCommunication", "Unexpected error in listening thread: " + e.getMessage(), e);
                    stopListeningInternal();
                    break;
                }
            }
            Log.d("Listening Status", "Listening thread ended");
        });
        readingThread.start();
    }

    public void stopListening() {
        stopListeningInternal();
        stopAutoReconnect(); // Also stop the background reconnection attempts
    }

    private static void stopListeningInternal() {
        if (readingThread != null) {
            readingThread.interrupt();
            try {
                readingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e("USBCommunication", "Interrupted while waiting for reading thread to join: " + e.getMessage());
                Thread.currentThread().interrupt();
            }
            readingThread = null;
        }
        if (usbSerialPort != null) {
            try {
                usbSerialPort.close();
                Log.d("USBCommunication", "USB serial port closed.");
            } catch (IOException e) {
                Log.e("USBCommunication", "Error closing USB serial port: " + e.getMessage());
            } finally {
                usbSerialPort = null;
            }
        }
    }


    public static void unregisterReceiver() {
        if (thiscontext != null) {
            try {
                thiscontext.unregisterReceiver(usbReceiver);
                Log.d("USBCommunication", "USB BroadcastReceiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.e("USBCommunication", "Receiver not registered or already unregistered: " + e.getMessage());
            }
        }
        stopAutoReconnect(); // Ensure scheduler is stopped when receiver is unregistered
        stopListeningInternal(); // Final cleanup
    }

    private static void openUsbDevice(UsbDevice device) {
        // If a port is already open and valid for this device, don't reopen.
        if (usbSerialPort != null && usbSerialPort.isOpen() && usbSerialPort.getDriver().getDevice().equals(device)) {
            Log.d("USBCommunication", "USB port already open and valid for device: " + device.getDeviceName());
            return;
        }

        // Clean up any existing state before attempting to open a new connection
        stopListeningInternal(); // This closes usbSerialPort and sets it to null

        UsbDeviceConnection connection = null;
        try {
            connection = usbManager.openDevice(device);
            if (connection == null) {
                Log.e("USB Error", "Failed to open USB connection for device: " + device.getDeviceName() + ". Device might be busy or permission revoked.");
                return;
            }

            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null) {
                Log.e("USB Error", "No suitable USB serial driver found for device: " + device.getDeviceName() + ". Vendor ID: " + device.getVendorId() + ", Product ID: " + device.getProductId());
                if (connection != null) connection.close();
                return;
            }

            List<UsbSerialPort> ports = driver.getPorts();
            if (ports.isEmpty()) {
                Log.e("USB Error", "No serial ports found on device: " + device.getDeviceName());
                if (connection != null) connection.close();
                return;
            }
            usbSerialPort = ports.get(0);

            usbSerialPort.open(connection);
            usbSerialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            listenForData(); // Starts a new, fresh reading thread
            Log.d("USB Status", "USB device opened and configured successfully: " + device.getDeviceName());
        } catch (IOException e) {
            Log.e("USB Error", "IOException during USB device setup for " + device.getDeviceName() + ": " + e.getMessage(), e);
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeEx) {
                    Log.e("USB Error", "Error closing connection after setup failure: " + closeEx.getMessage());
                }
            }
            usbSerialPort = null;
        } catch (SecurityException e) {
            Log.e("USB Error", "SecurityException (permission denied) when opening USB device " + device.getDeviceName() + ": " + e.getMessage(), e);
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeEx) {
                    Log.e("USB Error", "Error closing connection after security exception: " + closeEx.getMessage());
                }
            }
            usbSerialPort = null;
        } catch (Exception e) {
            Log.e("USB Error", "Unexpected error during USB device opening for " + device.getDeviceName() + ": " + e.getMessage(), e);
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception closeEx) {
                    Log.e("USB Error", "Error closing connection after unexpected exception: " + closeEx.getMessage());
                }
            }
            usbSerialPort = null;
        }
    }
}