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
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import java.io.IOException;
import java.util.HashMap;

public class USBCommunication {
    private static final String ACTION_USB_PERMISSION = "com.example.coverapp.USB_PERMISSION";

    UsbManager usbManager;
    UsbSerialPort usbSerialPort;
    Context context;
    Handler handler;
    Intent intent;
    boolean connected = false;

    public USBCommunication(Context context, Handler handler) {
        this.context = context;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.handler = handler;
        intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_APP_CALENDAR);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);



        // Register the broadcast receiver
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        findUsbDevice();
    }


    private void findUsbDevice() {
        assert usbManager != null;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (device.getVendorId() == 6790) {// Arduino Vendor ID
                connected = true;
                if (usbManager.hasPermission(device)) {
                    Log.d("USB Permission", "Already granted.");
                    openUsbDevice(device);
                } else {
                    Log.d("USB Permission", "Requesting permission.");
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(
                            context,
                            0,
                            new Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_IMMUTABLE // Make this flag immutable
                    );
                    usbManager.requestPermission(device, permissionIntent);
                    if (usbManager.hasPermission(device)) {
                        Log.d("USB Permission", "Now granted.");
                        openUsbDevice(device);
                    }

                }
            }
        }
        if (!connected) {
            System.exit(0);
        }
    }



    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                new Handler(context.getMainLooper()).postDelayed(() -> {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }, 500);
            }
        }
    };

    private Thread readingThread;

    private void listenForData() {
        readingThread = new Thread(() -> {
            byte[] buffer = new byte[64];
            int numBytes;
            Log.d("Listening Status: ", "started");

            while (!Thread.interrupted()) {
                try {
                    Log.d("USB Read", "Before reading...");
                    numBytes = usbSerialPort.read(buffer, 1000);
                    Log.d("USB Read", "Bytes read: " + numBytes);
                    if (numBytes > 0) {
                        String receivedData = new String(buffer, 0, numBytes);
                        Log.d("Received Data", receivedData);
                        if (receivedData.equals("SWIPE_RIGHT\r\n")) {
                            Log.d("Trigger:", "SWIPE_RIGHT");
                            swipeRightButton.callOnClick();
                        }
                        if (receivedData.equals("SWIPE_LEFT\r\n")) {
                            Log.d("Trigger:", "SWIPE_LEFT");
                            swipeLeftButton.callOnClick();
                        }
                        if (receivedData.equals("SWIPE_UP\r\n")) {
                            Log.d("Trigger:", "SWIPE_UP");
                            scrollUpButton.callOnClick();
                        }
                        if (receivedData.equals("SWIPE_DOWN\r\n")) {
                            Log.d("Trigger:", "SWIPE_DOWN");
                            scrollButton.callOnClick();
                        }
                        if (receivedData.equals("BUTTON1\r\n")) {
                            Log.d("Trigger:", "BUTTON1");
                            btnSimulateTouch.callOnClick();
                        }
                        if (receivedData.equals("BUTTON2\r\n")) {
                            Log.d("Trigger:", "BUTTON2");
                            btnDoubleTap.callOnClick();
                        }
                        if (receivedData.equals("BUTTON3\r\n")) {
                            Log.d("Trigger:", "BUTTON3");
                            home.callOnClick();
                        }

                    } else {
                        Log.d("USB Read", "No data received");
                    }
                } catch (IOException e) {
                    Log.e("USB Error", "Error reading data", e);
                    break;
                }
            }
            Log.d("Listening Status", "Listening thread ended");
        });
        readingThread.start();
    }

    public void stopListening() {
        if (readingThread != null) {
            readingThread.interrupt(); // Interrupt the thread to stop blocking
        }
    }

    public void unregisterReceiver() {
        context.unregisterReceiver(usbReceiver);
    }

    private void openUsbDevice(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e("USB Error", "Cannot open connection.");
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            Log.e("USB Error", "No suitable driver found.");
            return;
        }

        usbSerialPort = driver.getPorts().get(0);
        try {
            usbSerialPort.open(connection);
            usbSerialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            listenForData();
            Log.d("USB Status", "USB device opened successfully.");
        } catch (IOException e) {
            Log.e("USB Error", "Error opening USB port", e);
        }
    }

}
