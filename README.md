# DroidLink

See and control one Android phone from another over Wi-Fi.

One phone **shares** its screen. The other phone **controls** it: it sees the screen live and can tap, long-press, swipe, scroll, type, and press Back, Home and Recent apps.

- Works on Android 8.0 (API 26) and newer.
- No account, no server, no internet needed. The phones talk directly over the same Wi-Fi, or over one phone's hotspot.
- The same APK does both jobs. Install it on both phones.

## Install

1. Copy `DroidLink.apk` to both phones (Google Drive, a USB cable, a messaging app, anything).
2. Open it on each phone. Android asks you to allow installing from that app; allow it, then tap **Install**.

## Use it

**On the phone you want to control (the sharing phone):**

1. Open DroidLink and tap **Share this phone**.
2. Tap **Turn on remote control**. In the Accessibility list, open **DroidLink remote control** and switch it on.
   - On Android 13 and newer, apps installed from an APK are blocked from this at first. If Android says it's a *restricted setting*: go back to DroidLink, tap **Open App info**, tap **⋮** in the top corner, choose **Allow restricted settings**, then switch the service on again.
   - You can skip this step. The other phone will then only be able to watch.
3. Tap **Start sharing**, then **Start now** (choose **Entire screen** if Android asks).
4. DroidLink shows an **address** and a **6-digit PIN**.

**On the other phone (the controlling phone):**

1. Open DroidLink and tap **Control another phone**.
2. Tap the sharing phone under **Phones nearby**, or type the address.
3. Enter the PIN and tap **Connect**.

Controls at the bottom: Back, Home (long-press for the notification shade), Recent apps, Keyboard (type into whatever text box is selected on the other phone), and Disconnect.

To stop, tap **Stop sharing** in the app or **Stop sharing** in the notification on the sharing phone.

## How it works

| Part | File | What it does |
|---|---|---|
| Screen capture | `ScreenShareService.kt` | Foreground service using `MediaProjection` → `VirtualDisplay` → `ImageReader`. Frames are scaled to at most 1280 px, JPEG-encoded and sent at up to ~15 fps, only when the screen changes. Follows rotation. |
| Server | `HostServer.kt` | TCP server on port 47800 (falls back to a random port). One viewer at a time, PIN required, wrong PINs are slowed down and the PIN is replaced after 8 wrong tries. |
| Remote input | `RemoteControlService.kt` | Accessibility service that replays taps and swipes with `dispatchGesture`, presses Back/Home/Recents with `performGlobalAction`, and types with `ACTION_SET_TEXT`. |
| Discovery | `Nsd.kt` | Announces and finds sharing phones on the network with Android's built-in mDNS (`NsdManager`). |
| Viewer | `ViewerConnection.kt`, `RemoteScreenView.kt`, `ControlActivity.kt` | Receives and draws frames, turns each touch into a tap / long press / swipe in the other phone's coordinates. |
| Protocol | `Protocol.kt` | The small binary message format both sides use. |

## Limits

- Both phones have to be on the same local network. Connecting over the internet would need a relay server.
- One finger only (no pinch-zoom).
- The connection isn't encrypted. Use it on networks you trust; the PIN keeps strangers from connecting, but not from listening in on a shared public Wi-Fi.
- Screens Android protects (banking apps, password fields, DRM video) show up black. That's Android, not a bug.
- Typing replaces the focused field's text with its current text plus yours, so it works best in normal text boxes.

## Build it yourself

Open the folder in Android Studio and press Run, or from a terminal with the Android SDK installed:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

Push this folder to a GitHub repository and the included workflow (`.github/workflows/build.yml`) builds the APK for you on every push and attaches it to a release you can download on your phone.

The project is signed with a key in `app/droidlink.keystore` (password `droidlink`) so every build installs over the previous one. That is fine for personal use; make your own private key before publishing it anywhere.
