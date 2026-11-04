# AOmarchy user guide

AOmarchy runs Omarchy (Arch Linux with the Hyprland desktop) as one Android app. No root, no proot, no virtual
machine: Linux programs run directly on the tablet, with its GPU, sound, cameras and touch screen.

## What you need

- An Android tablet with Android 11 or later and a Qualcomm Adreno GPU. AOmarchy is developed on a POCO Pad 2 Pro
  (Adreno 810, HyperOS / Android 16).
- About 15 GB of free space: the system takes several GB once unpacked, and apps you add take more.
- A Wi-Fi connection for the first launch, unless you install from a local archive (see below).

## Installing

1. Install the AOmarchy APK and open it. You land on the **console**, not on the desktop:
   **概览 / Overview**, **系统 / System**, **应用 / Apps**, **控制 / Controls**, **设置 / Settings**.
2. Install the Linux system from **系统 → 安装系统**: **Online download** (about 2 GB, over Wi-Fi) or **Local
   archive**, a `lotfix-rootfs.tar.gz` you pick on the device. It unpacks the system; this takes a few minutes.
   - To install without a network, copy `lotfix-rootfs.tar.gz` to the tablet first, then pick it with
     **Local archive**; the file stays where it is.
   - Installing again replaces the system. It asks first, and the old system is moved aside (not deleted) until the
     new one is in place.
3. Fill in **设置 → 身份** (name and email, used by Git and the compose key) and **设置 → 键盘 → 系统布局** when you
   like: both are applied the next time the desktop starts. **设置 → 启动** makes the app go straight to the desktop.
4. Android asks for a few permissions at once:
   - **Microphone** and **Camera**: for calls and video in Linux apps.
   - **Location**: Android only shows the Wi-Fi network's name to apps allowed to use location. AOmarchy uses it
     for the network name in the bar.
   - **Notifications** (Android 13+): the session notification carries the **控制 / Controls** and **控制台 /
     Console** buttons, which is how you get back to the console from the desktop.
5. Press **进入桌面 / Enter desktop**. Omarchy's menu opens with **Super + Space**.

### Getting back to the console

While the desktop is in front, the small **A** handle in the top-left corner opens the console and leaves the Linux
session running. The session notification does the same (**控制台**), and its **控制** button opens the controls as a
half-screen sheet over the desktop.

### The Super key (recommended)

Android keeps the Super (Windows) key of hardware keyboards for itself. To use Omarchy's shortcuts with a hardware
keyboard, turn on AOmarchy's accessibility service: **Settings → Accessibility → AOmarchy → On**. It only
reads key presses while AOmarchy is in front.

## Android 12 and 13: child processes

Android 12 and 13 stop the programs an app starts after a while. Run this once from a computer with `adb` (Android 14
and later: **Developer options → Disable child process restrictions**):

```sh
adb shell settings put global settings_enable_monitor_phantom_procs false
```

## HyperOS / MIUI settings

Some gestures are taken by the system before AOmarchy sees them. Change these so the desktop's 3-finger gestures
work:

- **Settings → Additional settings → Gesture shortcuts → Take a screenshot**: turn off **Slide 3 fingers down**.
- **Settings → Additional settings → Navigation system**: turn on **Don't respond to three- and four-finger
  gestures**.

The split-screen and app-switching gestures with several fingers have no switch in Settings. With a computer and
`adb`:

```sh
adb shell settings put system enable_three_gesture 0
adb shell settings put system three_gesture_horizontal_ltr none
adb shell settings put system three_gesture_horizontal_rtl none
adb shell settings put global multi_finger_slide 0
```

HyperOS may restore them after a restart. To let AOmarchy turn app switching off again at every start, grant it
this permission once:

```sh
adb shell pm grant app.aomarchy android.permission.WRITE_SECURE_SETTINGS
```

HyperOS does not let apps change the split-screen setting: if 3-finger swipes stop working after a restart, run the
first three commands again.

To undo:

```sh
adb shell settings put system enable_three_gesture 1
adb shell settings put system three_gesture_horizontal_ltr split_ltr
adb shell settings put system three_gesture_horizontal_rtl split_rtl
adb shell settings put global multi_finger_slide 1
adb shell pm revoke app.aomarchy android.permission.WRITE_SECURE_SETTINGS
```

Swipes from the screen edges (home, back, notifications) stay with Android.

## Using the desktop

### Touch gestures

| Gesture | Action |
|---|---|
| 3 fingers left / right | Switch workspace |
| 3 fingers up | App launcher |
| 3 fingers down | Close the window |
| 3-finger tap | Full screen on / off |

You can change them in `~/.config/hypr/touch.lua`.

### On-screen keyboard

The keyboard (Squeekboard) appears by itself when you tap a text field, and hides while a hardware keyboard is
connected. The **⌨** icon in the bar shows or hides it at any time.

- It has a full PC layout: Esc, Tab, Ctrl, Super, Alt, AltGr, arrows, and F1–F12 on AltGr.
- **🌐** switches between the keyboard layouts you chose on the first-launch screen (Omarchy's). French,
  English and Arabic have the PC layout.
- Modifiers work like keyboard chords. Tap **Super**, then **Shift**, then **F** for Super + Shift + F; they turn off
  after the key. **Double-tap** a modifier to keep it on; tap it again to release it.
- It takes the colours of the current Omarchy theme.

### Chinese, Japanese and Korean text

The Arch system has no CJK font of its own, so AOmarchy copies the one Android ships
(`/system/fonts/NotoSansCJK-Regular.ttc`) into the Linux system when a session starts. Chinese, Japanese and Korean
text is then drawn normally in the desktop, the browser and the terminal instead of as empty boxes. (The first start
after installing or updating the app takes a moment longer for that copy.)

### Shutting down, rebooting and logging out

Omarchy's power menu works from the desktop:

- **Shutdown** closes the Linux session and returns to Android. No app may power the tablet off, so that is as far as
  it goes; use Android's power button for that.
- **Reboot** and **Log out** end the session and start a fresh one.

(Android has no logind; the app replaces the `systemd-inhibit` wrapper the power menu uses, which would otherwise fail
with a "shutdown failed" notification and leave the desktop running.)

### Android and Linux together

- **Copy and paste** works both ways between Android apps and Linux apps.
- **Drag and drop**: put AOmarchy and another app side by side (split screen or a floating window) and drag a photo,
  file or text onto the desktop. Files land in `~/Downloads`; text goes to the clipboard.
- **Android apps** are in the **Apps → Mobile Apps** menu and open in a floating window.

## Terminal mode

The same installed system, but with a shell instead of the desktop: no Hyprland, no bar, no app menu. It is the low
memory way to use Linux — measured on the tablet, a desktop session adds about 750 MB of system memory while the
terminal adds about 80 MB — and it starts in seconds, which makes `pacman -S ...` or a quick `git` cheap.

- Switch with **设置 → 启动方式 → 终端**. The big button on the console then says **进入终端 / Enter terminal**,
  and the Overview and Controls pages show the same state. Switch back the same way.
- Leaving the terminal (the back gesture or **‹ 控制台**) ends the shell; nothing keeps running in the background.
- The bar has **复制 / Copy**, **粘贴 / Paste**, **A− / A+** for the font size, **键盘 / Keyboard** and
  **重启 / Restart**.
- Copy a selection: long-press the text, then drag to extend it, then tap **复制**. With nothing selected, **复制**
  copies the visible screen.
- Scroll back by dragging up. The green **↓ 底部 / Bottom** pill (or a tap on the terminal) returns to the live
  output; typing anything does the same.
- The key strip at the bottom sends what a soft keyboard cannot: **Esc**, **Tab**, **^C**, **^D**, **^Z** and the
  arrow keys — so `vim`, `less`, `top` and cancelling a long command all work.
- Tapping the terminal shows or hides the keyboard.

The terminal is drawn by the app (no VNC, no X server): the pty comes from the app's own native code and the shell
runs inside the installed system, so the files, packages and settings are exactly the desktop's.

## Installing apps

- **App store**: GNOME Software, with Flathub. Flatpak apps run on the tablet's GPU. If the store is empty, run this in a terminal: `flatpak --user remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo`
- **Terminal**: `sudo pacman -S <package>` for Arch Linux packages, `yay -S <package>` for the AUR.
- AOmarchy's own components (graphics drivers, compositor, keyboard) are protected: an install or a full update
  (`sudo pacman -Syu`, `omarchy-update`) cannot replace them. If an update needs attention, a notification says so.

## Checking that everything works

Open a terminal and run:

```sh
andromarchy-selftest
```

It checks the GPU, sound, cameras, keyboard, clipboard, system services and app store, and prints one line per
check. Add `-v` to see why a check fails.

## Known limits

- **Flatpak apps are not isolated** from each other: Android gives apps no way to build the sandboxes Flatpak uses.
  They run with your user's access, like ordinary programs.
- **YouTube in Brave** decodes video on the processor. Hardware decoding works in `mpv` and `ffmpeg`.
- Old games that change the screen resolution (Allegro 4 and similar) are drawn full-screen but don't receive taps
  yet: play them in windowed mode.
- There is no real administrator account: `sudo` works for installing and configuring, but cannot change the tablet's
  own system.

## Credits

AOmarchy's Android app is based on [lfdevs/anland-termux](https://github.com/lfdevs/anland-termux) (GPL-3.0). It
uses lfdevs's Mesa for Android, termux-va and XWayland patches, and builds on Omarchy, Hyprland, Arch Linux ARM, Mesa,
Squeekboard, hyprgrass, PipeWire, Flatpak and fakechroot.
