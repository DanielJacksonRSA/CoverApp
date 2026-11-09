# SerialTouch

**A physical button for your phone.** Wire up a microcontroller, press a key, and your Android device taps, swipes, scrolls, or jumps home. No finger on the glass.

```
  controller  ──USB serial──►  SerialTouch  ──gesture──►  your screen
  (Arduino)      '1'..'7'       (a11y service)   tap/swipe
```

SerialTouch listens to a USB-serial board over an OTG cable. The board sends one character; the app turns it into an on-screen gesture through Android's AccessibilityService. That's the whole idea.

Good for accessibility rigs, kiosks, hands-free setups, test jigs, or anything where a human finger isn't practical.

## Why it exists

Android has no clean way to let hardware drive the touchscreen. SerialTouch closes that gap with the smallest possible protocol (a single byte per action) and a connection that heals itself when the cable is yanked or the board resets.

## What it does

* Auto-detects the controller and connects the moment it's plugged in.
* Reconnects on its own. A background watchdog retries every few seconds after a drop.
* Ships with seven actions: tap, double-tap, home, scroll up, scroll down, swipe left, swipe right.
* Stays out of the way. Gestures run through a hidden overlay, so nothing shows on screen.
* Extends in two edits. One button, one switch case, and you have a new action.

## The protocol

One ASCII digit per action, streamed over serial.

| Byte | Action      | On screen                                |
|:----:|-------------|------------------------------------------|
| `1`  | Tap         | Single tap at the center of the display  |
| `2`  | Double tap  | Two quick taps at the center             |
| `3`  | Home        | Go to the home screen                    |
| `4`  | Scroll up   | Scroll the focused list backward         |
| `5`  | Scroll down | Scroll the focused list forward          |
| `6`  | Swipe left  | Horizontal swipe, right to left          |
| `7`  | Swipe right | Horizontal swipe, left to right          |

Line settings: **2400** baud, **8** data bits, **1** stop bit, **no** parity.

A minimal Arduino sketch:

```cpp
void setup() {
  Serial.begin(2400);
}

void loop() {
  if (digitalRead(2) == LOW) { Serial.print('1'); delay(200); } // tap
  if (digitalRead(3) == LOW) { Serial.print('3'); delay(200); } // home
  if (digitalRead(4) == LOW) { Serial.print('7'); delay(200); } // swipe right
  // wire up the rest the same way
}
```

## Getting started

You'll need Android Studio (AGP 8.8, JDK 11+), a device on Android 12 (API 31) or newer with USB host support, a USB-serial board, and an OTG cable.

Build and install onto a connected device:

```bash
git clone <your-repo-url>
cd SerialTouch
./gradlew installDebug
```

`local.properties` is not committed. Android Studio generates it, or you can write one yourself with `sdk.dir=/path/to/Android/sdk`.

Then turn it on:

1. Open SerialTouch and tap **Enable SerialTouch**.
2. Under Settings > Accessibility > SerialTouch Controller, flip the service on.
3. Plug the controller in over OTG and approve the USB prompt.
4. Press a button. The phone responds.

## How it fits together

```
app/src/main/java/com/example/coverapp/
  MainActivity.java          entry screen, shortcut to Accessibility settings
  AccessibilityService.java  hidden overlay, gestures, global actions
  USBCommunication.java      USB discovery, reconnect watchdog, byte -> action
```

A byte becomes a gesture in three hops:

1. The accessibility service connects, inflates a hidden overlay (`floating_bar.xml`) with one button per action, and wires each button to a gesture or global action.
2. It hands off to `USBCommunication.start(...)`, which finds the controller, opens the port, and starts a reader thread.
3. Each incoming digit maps to a button, whose click runs on the UI thread and fires the gesture.

The overlay buttons are the seam between input and action. The USB layer never touches gesture APIs. It just clicks buttons.

## Tuning

Every knob is a constant at the top of its class.

| Constant                | File                    | Purpose                            |
|-------------------------|-------------------------|------------------------------------|
| `CONTROLLER_VENDOR_ID`  | `USBCommunication.java` | USB vendor ID the app connects to  |
| `BAUD_RATE`             | `USBCommunication.java` | Serial baud rate (match firmware)  |
| `RECONNECTION_DELAY_MS` | `USBCommunication.java` | Reconnect retry interval           |
| `SWIPE_DURATION_MS`     | `AccessibilityService`  | Length of a swipe                  |
| `TAP_DURATION_MS`       | `AccessibilityService`  | Length of a tap                    |

One gotcha worth knowing: `res/xml/device_filter.xml` (the "open app on attach" prompt) and `CONTROLLER_VENDOR_ID` (the runtime connection) are set independently and currently hold different values. If attach detection or auto-connect acts up, set both to your board's real vendor ID.

To add an action: drop a hidden button into `floating_bar.xml`, bind it in `AccessibilityService.bindButtons()` as a `static Button`, and add a `case` in `USBCommunication.dispatchTrigger(...)`.

## Permissions and privacy

SerialTouch runs as an AccessibilityService, which is a powerful permission. It uses that power for two things only: performing gestures, and reading the view hierarchy to find something scrollable. It does not log, store, or send your screen anywhere. Read the source before enabling it, the same as you would for any accessibility app.

## License

MIT. See [`LICENSE`](LICENSE).
