# Lockscreen Clock Transparency - LSPosed Module

An LSPosed / Xposed module designed to dynamically adjust the transparency (alpha) of the lockscreen clock on Motorola devices (specifically targeting Motorola's Depth Effect Wallpaper clocks and Motorola SystemUI ClockFace) as well as AOSP-based lockscreens.

## Features

- **Adjustable Opacity**: Slider in the settings app from `0%` (completely transparent/hidden) to `100%` (fully opaque).
- **Targeted Hooks**:
  - `com.motorola.deptheffect`:
    - Hooks `com.motorola.deptheffect.clockface.DepthEffectSession`
    - Controls `first_time_container` (back clock digits) and `second_time_container` (front clock digits).
    - Preserves alpha across time ticks and clock style changes.
  - `com.android.systemui`:
    - Hooks `com.motorola.keyguard.clock.MotoClockFaceController` (`largeClockBg`, `largeClockFg`, `smallClockView`).
    - Hooks `com.android.systemui.shared.clocks.view.MotoClockFaceContainer.setAlpha` so system fade-in and unlock animations smoothly modulate with your custom transparency.
    - Fallback hooks for AOSP `KeyguardClockSwitch` and `AnimatableClockView`.

## How to Build

1. Open this project in **Android Studio** (or build with Gradle):
   ```bash
   cd lsposed_clock_transparency
   ./gradlew assembleDebug
   ```
2. The built APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

## Installation & Setup

1. Install `app-debug.apk` onto your rooted device.
2. Open the **LSPosed Manager** app.
3. Tap **Modules**, locate **Lockscreen Clock Transparency**, and enable it.
4. Ensure the scope has both:
   - `System Framework / SystemUI` (`com.android.systemui`)
   - `Motorola Depth Effect` (`com.motorola.deptheffect`)
5. Reboot SystemUI or reboot the device.
6. Open the app launcher, open **Lockscreen Clock Transparency**, and adjust the slider to your preferred opacity!
