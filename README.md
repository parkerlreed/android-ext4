# android-ext4

(Auto) Mount external EXT4 USB drives with root.

USB ext4 Mount is a small Android app that mounts an ext2/3/4 USB drive at a fixed path (`/data/usb` by default) and can also show it to every app as `/sdcard/USB`, so file managers, video players and galleries can use it like normal storage.

## Requirements

- A rooted device with a `su` binary (KernelSU, Magisk or APatch)
- USB host (OTG) support
- Android 8.0 or newer; showing the drive in `/sdcard` targets Android 11+ (FUSE-based shared storage)

## Features

- Lists USB partitions with label, size and filesystem type
- Mounts in the global mount namespace, so the mount is visible to adb, root apps and every other app (not just this one)
- **Show in /sdcard**: binds the drive to `/sdcard/USB` with the right SELinux label, then rescans media so gallery and music apps index it
- Optional `e2fsck -p` before mounting, when the ROM ships `e2fsck`
- Auto-mount when a drive is plugged in, and live refresh on plug and unplug
- Safe unmount: tells you which apps are still using the drive and can close them for you
- Hands files that apps create on the drive back to your desktop user (uid 1000) when unmounting
- Material 3 UI that follows the system colours and dark mode, including a themed launcher icon

## Install

Build it yourself (JDK 17 or newer and the Android SDK are needed):

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs directly.

After installing, grant the app root access. KernelSU does not show a prompt: enable it in the KernelSU manager's Superuser list.

## Usage

1. Plug in the drive and open the app.
2. Pick the partition, check the mount point and options (default `rw,noatime`), then tap **Mount**.
3. Tap **Unmount** before unplugging. If the drive is busy, the message names the apps using it, and **Force** closes them and tries again.

With **Auto-mount on attach** enabled, choose "Always" when Android asks which app to open for the USB device, and the drive mounts as soon as it is plugged in.

## Using the drive on a Linux desktop too

Files that apps save through `/sdcard/USB` are written by Android's MediaProvider, so they are created as uid 10318 / gid 1023 (the exact uid differs per device). The app gives them back to `1000:1000` when you unmount, but MediaProvider still needs access to folders you create on the desktop. Run this once on the desktop with the drive mounted, adjusting the MediaProvider uid to match your phone (`adb shell stat -c %u /sdcard/Download`):

```sh
sudo find /path/to/drive -xdev -type d -exec setfacl \
  -m u:10318:rwx,g:1023:rwx,d:u:10318:rwx,d:g:1023:rwx,d:u:1000:rwx,d:g:1000:rwx {} +
```

The default ACLs are inherited, so new folders keep working without running it again.

## How it works

The app copies [`usbmount.sh`](app/src/main/assets/usbmount.sh) into its private files directory and runs it with `su`. You can also run it by hand:

```sh
su -c 'sh /data/data/com.example.usbext4mount/files/usbmount.sh list'
#   list                               USB partitions: DEV|SECTORS|TYPE|LABEL|MOUNTPOINT
#   wait SECS                          wait for a USB ext partition to appear
#   mount DEV MNT [OPTS] [FSCK] [NAME] mount DEV at MNT; NAME also shows it as /sdcard/NAME
#   umount MNT [force]                 unmount MNT and its /sdcard bind
#   status MNT                         show what is mounted at MNT
```

Some details that took digging to get right:

- **Mount namespace.** `su` normally runs in the calling app's private mount namespace, where a mount is invisible to everyone else. The script re-runs itself with `nsenter -t 1 -m` so mounts happen in init's namespace and propagate to all apps.
- **Finding USB disks.** Phones with UFS storage expose their internal disks as `sd*` too, so disks are matched by their sysfs path containing `usb`.
- **Showing it in /sdcard.** `/sdcard` is a FUSE filesystem served by MediaProvider from `/data/media/0`. The drive is bind-mounted to `/data/media/0/USB`, and MediaProvider serves it to every app with normal storage permissions.
- **SELinux labels.** MediaProvider may only read files labelled `media_rw_data_file`. Mounting with `defcontext=u:object_r:media_rw_data_file:s0` gives that label to every file without one, without writing anything to the drive and without changing SELinux policy. (`context=` was refused on the test device, even with an added SELinux rule.) Files written while the drive was mounted without the label can end up with an invalid label and need `chcon -R u:object_r:media_rw_data_file:s0 <dir>`.
- **Busy unmounts.** With FUSE-BPF, the kernel keeps references to every file apps have looked up through `/sdcard`, even after they are closed. Unmount drops the dentry and inode caches (`echo 2 > /proc/sys/vm/drop_caches`) to release them before reporting the drive as busy. Background apps such as Google Photos may also start reading new videos from the drive; **Force** closes those apps but never MediaProvider or system processes.
- **Device quirks.** Android's `mksh` uses 32-bit arithmetic, so sizes are passed to the app as sector counts. Toybox `nsenter` treats options after the command as its own, so the script runs whole script files under `nsenter` instead of `sh -c`.

## Limitations

- Mounts do not survive a reboot. Reopen the app, or replug the drive with auto-mount enabled.
- The desktop ACLs name MediaProvider's uid, which can change after a factory reset.
- Only ext2/3/4 is supported. Android's own storage handling (vold) covers FAT and exFAT.

## License

[GPL-3.0](LICENSE)
