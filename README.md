# DroidLink

[![Latest release](https://img.shields.io/github/v/release/TymonBitesTheDust/Driod-Link?label=latest%20release)](https://github.com/TymonBitesTheDust/Driod-Link/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/TymonBitesTheDust/Driod-Link/total)](https://github.com/TymonBitesTheDust/Driod-Link/releases)
[![Build APK](https://github.com/TymonBitesTheDust/Driod-Link/actions/workflows/build.yml/badge.svg)](https://github.com/TymonBitesTheDust/Driod-Link/actions/workflows/build.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84)

See and control one Android phone from another over Wi-Fi.

One phone **shares** its screen. The other phone **controls** it: it sees the screen live and can tap, long-press, swipe, scroll, type, and press Back, Home and Recent apps.

- Works on Android 8.0 (API 26) and newer.
- No account, no server, no internet needed. The phones talk directly over the same Wi-Fi, or over one phone's hotspot.
- The same APK does both jobs. Install it on both phones.

## Download

**[⬇ Download the latest DroidLink.apk](https://github.com/TymonBitesTheDust/Driod-Link/releases/latest/download/DroidLink.apk)**

Open this link on each phone to download the newest version directly. Older builds are on the [Releases page](https://github.com/TymonBitesTheDust/Driod-Link/releases).

## Install

1. Download `DroidLink.apk` on both phones using the link above, or copy it over any way you like (Google Drive, a USB cable, a messaging app).
2. Open it on each phone. Android asks you to allow installing from that app; allow it, then tap **Install**. If Play Protect warns about an unknown app, tap **Install anyway**.

To update, download the latest APK again and install it over the old one. Your settings are kept.

## Use it

**On the phone you want to control (the sharing phone):**

1. Open DroidLink and tap **Share this phone**.
2. Tap **Turn on remote control**. In the Accessibility list, open **DroidLink remote control** and switch it on.
   - On Android 13 and newer, Android blocks this at first with a **Restricted setting** message. Follow [Allow restricted settings](#allow-restricted-settings-android-13-and-newer) below. It takes about 30 seconds and you only do it once.
   - You can skip this step. The other phone will then only be able to watch.
3. Tap **Start sharing**, then **Start now** (choose **Entire screen** if Android asks).
4. DroidLink shows an **address** and a **6-digit PIN**.

**On the other phone (the controlling phone):**

1. Open DroidLink and tap **Control another phone**.
2. Tap the sharing phone under **Phones nearby**, or type the address.
3. Enter the PIN and tap **Connect**.

Controls at the bottom: Back, Home (long-press for the notification shade), Recent apps, Keyboard (type into whatever text box is selected on the other phone), and Disconnect.

To stop, tap **Stop sharing** in the app or **Stop sharing** in the notification on the sharing phone.

## Allow restricted settings (Android 13 and newer)

When you try to switch on **DroidLink remote control**, Android may show:

> **Restricted setting**
> For your security, this setting is unavailable.

That is expected. Android 13 and newer block accessibility access for apps installed from an APK file instead of an app store, until you unlock it yourself. DroidLink needs that access to tap and swipe for the other phone. The Share this phone screen in the app walks you through the same steps.

Do this once, on the phone being controlled:

1. **Trigger the message.** In DroidLink, tap **Share this phone**, then **Turn on remote control**. Open **DroidLink remote control** and try to switch it on. When the *Restricted setting* message appears, tap **OK**.
   This step matters: the unlock option only shows up after Android has blocked the setting once.
2. **Open DroidLink's App info.** Tap **Open App info** on the Share this phone screen, or go to **Settings → Apps → DroidLink**.
3. **Allow restricted settings.** Tap **⋮** in the top-right corner, then **Allow restricted settings**. Confirm with your PIN, pattern or fingerprint.
4. **Switch it on.** Tap **Turn on remote control** again, or go to **Settings → Accessibility** and open **Installed apps** (Samsung) or **Downloaded apps** (Pixel). Open **DroidLink remote control**, switch it on and tap **Allow**.

Back in DroidLink, the **Remote control** card now shows **On**. The controlling phone doesn't need any of this.

### No ⋮ in App info, or no "Allow restricted settings"?

Both only appear after Android has recorded that it blocked the setting, and an App info page that was already open keeps showing the old version.

1. Close Settings completely: open Recent apps and swipe Settings away.
2. Do step 1 again: tap the **DroidLink remote control** switch so the *Restricted setting* message appears, then tap **OK**.
3. Open **Settings → Apps → DroidLink** fresh. The **⋮** should now be in the top-right corner.

Still missing? Restart the phone and repeat. As a last resort, with USB debugging on and the phone connected to a computer, run:

```
adb shell appops set app.droidlink ACCESS_RESTRICTED_SETTINGS allow
```

Then switch on **DroidLink remote control** as in step 4.

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

The APK is created at `app/build/outputs/apk/release/app-release.apk`.

The project is signed with a key in `app/droidlink.keystore` (password `droidlink`) so every build installs over the previous one. That is fine for personal use; make your own private key before publishing it anywhere.

## Publishing a new release

Releases are published automatically by the workflow in `.github/workflows/build.yml`:

1. Optional: raise `versionCode` and `versionName` in `app/build.gradle.kts` so you can tell versions apart in **App info**.
2. Commit and push to `main`.
3. GitHub Actions builds the APK and publishes it as a new release called **DroidLink build N**. Follow it on the [Actions tab](https://github.com/TymonBitesTheDust/Driod-Link/actions).

Each new build becomes the **Latest** release, so the download link at the top of this README always points to it. Keep the file name `DroidLink.apk`: that's the name the link looks for.

To push a change without publishing a new build (for example, a README edit), put `[skip ci]` in the commit message.
