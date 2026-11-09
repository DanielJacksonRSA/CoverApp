<div align="center">

# ⚡ SerialTouch

### Drive your Android phone with real, physical buttons.

*Plug in a microcontroller. Press a button. Watch your phone tap, swipe, and scroll — no fingers required.*

<br/>

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-31-blue)
![Target SDK](https://img.shields.io/badge/targetSdk-35-blue)
![Language](https://img.shields.io/badge/language-Java-orange?logo=openjdk&logoColor=white)
![Interface](https://img.shields.io/badge/interface-USB%20Serial-black?logo=usb&logoColor=white)
![License](https://img.shields.io/badge/license-All_Rights_Reserved-red)

</div>

---

## 🎯 What is this?

**SerialTouch** turns any USB-serial microcontroller (Arduino, Teensy, or a CH340-based board) into a
**physical remote control for your Android device**.

The board sends a single character over the serial port; SerialTouch catches it and performs the
matching on-screen gesture through Android's **AccessibilityService** — a center tap, a double tap,
a directional swipe, a scroll, or a jump to the home screen.

> Think of it as a programmable "macro pad" for your phone. Great for accessibility rigs,
> kiosk setups, hands-free control, test automation jigs, or any build where a human finger
> isn't practical.

```
┌──────────────┐   USB serial    ┌─────────────────────┐   gesture    ┌───────────────┐
│  Controller  │ ──────────────► │     SerialTouch     │ ───────────► │  Your Android │
│  (Arduino)   │   '1'..'7'      │ AccessibilityService│  tap/swipe   │    screen     │
└──────────────┘                 └─────────────────────┘              └───────────────┘
```

---

## ✨ Features

- 🔌 **Plug-and-play USB** — auto-detects the controller and connects on attach.
- ♻️ **Self-healing connection** — a background watchdog reconnects every few seconds if the cable
  is pulled or the board resets.
- 👆 **Seven mapped actions** out of the box — tap, double-tap, home, scroll up/down, swipe left/right.
- 🪟 **Invisible overlay** — actions are dispatched through a hidden accessibility overlay; nothing
  clutters your screen.
- 🧩 **Trivially extensible** — add a new gesture by adding one button and one `case`.

---

## 🎮 Serial protocol

The controller speaks a dead-simple protocol: **one ASCII digit per action**, streamed over serial.

| Byte | Action        | What happens on screen                     |
|:----:|---------------|--------------------------------------------|
| `1`  | Tap           | Single tap at the center of the display    |
| `2`  | Double tap    | Two quick taps at the center               |
| `3`  | Home          | Go to the home screen                      |
| `4`  | Scroll up     | Scroll the focused list backward           |
| `5`  | Scroll down   | Scroll the focused list forward            |
| `6`  | Swipe left    | Horizontal swipe, right → left             |
| `7`  | Swipe right   | Horizontal swipe, left → right             |

**Serial line settings**

| Setting   | Value |
|-----------|-------|
| Baud rate | `2400` |
| Data bits | `8` |
| Stop bits | `1` |
| Parity    | `None` |

### Example firmware (Arduino)

```cpp
void setup() {
  Serial.begin(2400);
}

void loop() {
  if (digitalRead(2) == LOW) { Serial.print('1'); delay(200); } // tap
  if (digitalRead(3) == LOW) { Serial.print('3'); delay(200); } // home
  if (digitalRead(4) == LOW) { Serial.print('7'); delay(200); } // swipe right
  // ...wire up the rest the same way
}
```

---

## 🚀 Getting started

### Prerequisites

- Android Studio (AGP **8.8**, JDK **11+**)
- An Android device running **Android 12 (API 31)** or newer with **USB host** support
- A USB-serial controller + an OTG cable

### Build & install

```bash
git clone <your-repo-url>
cd SerialTouch
./gradlew installDebug      # build and push to a connected device
```

> **Heads up:** `local.properties` is intentionally **not** committed. Android Studio generates it
> automatically, or create it yourself with `sdk.dir=/path/to/Android/sdk`.

### Enable the service

1. Launch **SerialTouch** and tap **Enable SerialTouch**.
2. In **Settings → Accessibility → SerialTouch Controller**, toggle the service **on**.
3. Plug in your controller via OTG and approve the USB permission prompt.
4. Press a button on your controller — the phone responds instantly. 🎉

---

## 🏗️ Architecture

```
app/src/main/java/com/example/coverapp/
├── MainActivity.java         → Entry screen; shortcut to Accessibility settings
├── AccessibilityService.java → Builds the hidden overlay, performs gestures/global actions
└── USBCommunication.java     → USB discovery, connection watchdog, serial → action mapping
```

**How a button press becomes a gesture**

1. `AccessibilityService` connects, inflates a hidden overlay (`floating_bar.xml`) with one
   `Button` per action, and wires each button to a gesture or global action.
2. It then hands control to `USBCommunication.start(...)`, which locates the controller, opens the
   serial port, and spins up a reader thread.
3. Each incoming digit is mapped to the matching overlay button and that button's click handler is
   invoked **on the UI thread**, triggering the gesture.

The overlay buttons act as a clean seam between "input source" and "action": the USB layer never
touches gesture APIs directly — it just clicks buttons.

---

## 🔧 Configuration & tuning

All tunables live as constants at the top of their class:

| Constant                 | File                    | Purpose                                   |
|--------------------------|-------------------------|-------------------------------------------|
| `CONTROLLER_VENDOR_ID`   | `USBCommunication.java` | USB vendor ID the app connects to         |
| `BAUD_RATE`              | `USBCommunication.java` | Serial baud rate (match your firmware)    |
| `RECONNECTION_DELAY_MS`  | `USBCommunication.java` | Reconnect watchdog interval               |
| `SWIPE_DURATION_MS`      | `AccessibilityService`  | Length of swipe gestures                  |
| `TAP_DURATION_MS`        | `AccessibilityService`  | Length of a single tap                    |

> ⚠️ **Vendor ID consistency:** `res/xml/device_filter.xml` (which controls the "open app on attach"
> prompt) and `CONTROLLER_VENDOR_ID` (which controls the runtime connection) are configured
> independently and currently hold **different** values. If attach-detection or auto-connect misbehaves,
> make both match your board's actual vendor ID.

### Add a new action

1. Add a hidden `Button` to `res/layout/floating_bar.xml`.
2. Bind it in `AccessibilityService.bindButtons()` and expose it as a `static Button`.
3. Add a `case` for its trigger character in `USBCommunication.dispatchTrigger(...)`.

---

## ⚠️ Permissions & privacy

SerialTouch requests an **AccessibilityService**, which is a powerful capability. This app uses it
**only** to perform gestures and read the view hierarchy for scrolling — it does **not** log, store,
or transmit screen content anywhere. Review the source before enabling it, as you should with any
accessibility app.

---

## 📜 License

All rights reserved. See [`LICENSE`](LICENSE).
