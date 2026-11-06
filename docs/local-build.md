# Building locally (WSL)

For small iterations the GitHub Actions build (about 6–10 minutes per round trip) is too slow: the same APK builds
inside WSL in **10–15 seconds** once the toolchain is warm. This page records the setup that exists on the development
machine and how to rebuild it from scratch.

## Usage

```powershell
pwsh -File tools/build-local.ps1              # signed release APK -> ..\deploy-test\AOmarchy-local.apk
pwsh -File tools/build-local.ps1 -Install     # ... and adb install -r it on the connected tablet
pwsh -File tools/build-local.ps1 -Clean       # gradle clean first
pwsh -File tools/build-local.ps1 -Task assembleDebug
```

`tools/build-local.ps1` calls `/root/build-aomarchy.sh` inside WSL, which

1. `rsync`s this checkout (without `.git/` and any `build/`) to `/root/aomarchy` — Gradle's own `build/` stays there, so
   the second and later builds are incremental;
2. writes `apk/local.properties` with `sdk.dir=/opt/android-sdk`;
3. runs `./gradlew --no-daemon <task>` with `AOMARCHY_SIGNING=/root/keys/signing.properties`;
4. copies the newest APK back to `..\deploy-test\AOmarchy-local.apk`.

The APK is signed with the **release key**, so `adb install -r` updates an installed release build in place (no
uninstall, the 5 GB rootfs stays).

## What is installed (and how to recreate it)

WSL: Ubuntu 26.04 on WSL 2. The Windows `.wslconfig` had `networkingMode=Mirrored`, which fails on this host and makes
WSL fall back to **no network at all**; it is now `networkingMode=nat` (backup: `C:\Users\wind\.wslconfig.bak`).
Changing it needs `wsl --shutdown`.

```bash
# 1. JDK and basic tools
apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y \
    openjdk-21-jdk-headless unzip curl file

# 2. Android SDK command-line tools (adjust the version in the URL if needed)
mkdir -p /opt/android-sdk/cmdline-tools && cd /tmp
curl -sSLo cmdtools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o cmdtools.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

# 3. Platform, build-tools, NDK and CMake (same versions as .github/workflows/build.yml)
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'

# 4. Release signing material — copied from the Windows checkout, never committed
mkdir -p /root/keys
cp /mnt/e/AndroDSH/signing/aomarchy-release.jks /root/keys/
sed -E 's|^storePath=.*|storePath=/root/keys/aomarchy-release.jks|' \
  /mnt/c/Users/wind/AppData/Local/Temp/aomarchy-debug/signing.properties \
  > /root/keys/signing.properties
chmod 600 /root/keys/*
```

`/root/build-aomarchy.sh` (see above) is the wrapper the PowerShell script calls; it exports `JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT` and `AOMARCHY_SIGNING` and maps
`../deploy-test/AOmarchy-local.apk` back onto the Windows drive.

## Notes and limits

- **Native code is built too.** `apk/build.gradle` compiles the NDK executables (`libaomarchy.so`,
  `liblotfixrun.so`, `libtermuxva.so`) and CMake builds `libaomarchy_consumer.so`; the first build downloads nothing
  extra (NDK is local) and later builds only relink changed sources.
- **Signing differs from CI only in timestamps** — same key, same code, so the APK is interchangeable for testing but
  its SHA-256 differs from the release asset.
- **D-Pad/emulator not involved**: install with the tablet connected to Windows `adb`; the build itself happens in WSL.
- **WSL `.wslconfig` change** affects every WSL distro on the machine: `networkingMode=nat` instead of `Mirrored`
  (mirrored mode could not start here). Restore the backup if a host-side VPN needs mirrored loopback.
- The Gradle daemon is disabled (`--no-daemon`) on purpose: a cold-but-warm-cache build is ~10 s, and a daemon would
  keep a JVM resident in WSL's 8 GB memory.
- For a release the CI workflow is still the source of truth (`.github/workflows/release.yml`, tag `v*`); local builds
  are for iteration and device testing.

## Publishing the rootfs archive

`build/build-rootfs.sh` (PC/WSL) and `build/build-rootfs-native.sh` (on-tablet) both produce
`ao-rootfs.tar.gz` plus its `.sha256`. The app offers **two online sources** and the user picks one explicitly on
系统 → 安装系统; there is no automatic fallback, so keep both mirrors carrying the same archive.

| Source | URL | Where the file lives |
| --- | --- | --- |
| Tencent Cloud mirror (default) | `https://www.yhbd.top/downloads/ao-rootfs.tar.gz` | `C:\wwwroot\yhbd\public\downloads\` on SSH host `yhbd-server` |
| GitHub Release | `https://github.com/wind-wh/AOmarchy/releases/download/rootfs/ao-rootfs.tar.gz` | asset on the fixed `rootfs` tag |

Publishing to the Tencent Cloud mirror:

```
scp ao-rootfs.tar.gz          yhbd-server:C:/wwwroot/yhbd/public/downloads/ao-rootfs.tar.gz
scp ao-rootfs.tar.gz.sha256   yhbd-server:C:/wwwroot/yhbd/public/downloads/ao-rootfs.tar.gz.sha256
```

The host is a Windows Server 2022 (Tencent Cloud Lighthouse) running nginx as the `NGINX (yhbd.top)` service
under `NT AUTHORITY\SYSTEM`. `C:\nginx\conf\nginx.conf` has exact-match locations for both paths that disable
gzip, set the MIME type, send `Cache-Control: no-cache` and extend `send_timeout` to 300s for slow mobile
downloads; nginx static files support Range requests already. An ordinary Administrator SSH session **cannot**
reload it (`OpenEvent("Global\ngx_reload_...") Access is denied`). Back up the config, run
`nginx.exe -t -c C:\nginx\conf\nginx.conf`, then reload through a one-shot SYSTEM task:

```
schtasks /create /tn aom-nginx-reload /tr "C:\nginx\nginx.exe -s reload -p C:\nginx" /sc once /st 00:00 /ru SYSTEM /f
schtasks /run /tn aom-nginx-reload
schtasks /delete /tn aom-nginx-reload /f
```

Publishing to GitHub: create one `rootfs` tag / GitHub Release and attach `ao-rootfs.tar.gz` (and its checksum)
there. Do **not** commit the archive to git: a ~2 GB file exceeds GitHub's 100 MB per-file limit and would bloat
every clone forever. Keeping it on a dedicated tag means ordinary `v*` APK releases never invalidate the URL —
the `release.yml` workflow only uploads APKs, so `releases/latest/download/...` would 404 after the next app
release. Replacing the rootfs later = overwrite the asset on that same `rootfs` release, no code change needed.

For offline installs, copy the archive to the tablet under the exact name `ao-rootfs.tar.gz`
(`Installer.LOCAL_NAME`); the old `lotfix-rootfs.tar.gz` name is no longer recognised.

Until `ao-rootfs.tar.gz` is actually built and uploaded, both online URLs return 404 and offline install from a
local archive is the only working path.
