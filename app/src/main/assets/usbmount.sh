#!/system/bin/sh
# Root helper for UsbExt4Mount. Run as root via su.
#
#   usbmount.sh list                       one line per USB partition: DEV|SECTORS|TYPE|LABEL|MOUNTPOINT
#   usbmount.sh wait SECS                  wait until a USB ext partition appears
#   usbmount.sh mount DEV MNT [OPTS] [FSCK] [NAME] [SCAN]
#                                          mount DEV (ext2/3/4) at MNT; FSCK=1 runs e2fsck -p first;
#                                          NAME also shows it to apps as /sdcard/NAME; SCAN=1 then
#                                          rescans media so media apps index the drive
#   usbmount.sh umount MNT [force] [SCAN]  unmount MNT (and its /sdcard bind); force closes apps
#                                          still reading the drive, then detaches if needed;
#                                          SCAN=1 rescans media afterwards
#   usbmount.sh cleanup MNT                detach MNT if its drive was unplugged while mounted
#   usbmount.sh status MNT                 show what is mounted at MNT

# su usually inherits the app's private mount namespace, so a mount made there
# would be invisible to everything else. Re-run ourselves inside init's
# (global) namespace; mounts there propagate to adb shell and other apps.
if [ -z "$USBMOUNT_NS" ] && [ "$(readlink /proc/self/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ]; then
    if command -v nsenter >/dev/null 2>&1; then
        export USBMOUNT_NS=1
        exec nsenter -t 1 -m sh "$0" "$@"
    fi
    echo "W: nsenter not found; mount will only be visible in this namespace"
fi

die() { echo "E: $*"; exit 1; }

# /sdcard is served by MediaProvider from this directory, and MediaProvider may
# only read files with this SELinux type.
MEDIA_ROOT=/data/media/0
MEDIA_CONTEXT=u:object_r:media_rw_data_file:s0
MEDIA_RW_GID=1023
# Owner given back to files apps created through /sdcard (uid 1000 is the
# first user on most Linux desktops).
DESKTOP_OWNER=1000:1000
# Which connection of the drive each mount point was mounted from; kept next to
# this script in the app's private files directory.
STATE_DIR=${0%/*}/mounts

# Kernel names (sda, sdb, ...) of disks attached over USB.
usb_disks() {
    for d in /sys/block/sd*; do
        [ -e "$d" ] || continue
        case "$(readlink -f "$d")" in *usb*) echo "${d##*/}" ;; esac
    done
}

# Partitions of a disk, or the disk itself when it has no partition table.
disk_parts() {
    found=
    for p in "/sys/block/$1/$1"*; do
        [ -e "$p/partition" ] || continue
        echo "${p##*/}"
        found=1
    done
    [ -n "$found" ] || echo "$1"
}

dev_node() {
    for n in "/dev/block/$1" "/dev/$1"; do
        [ -b "$n" ] && { echo "$n"; return 0; }
    done
    return 1
}

# MAJ:MIN of a block device node, e.g. 8:1.
majmin() {
    cat "/sys/class/block/${1##*/}/dev" 2>/dev/null
}

# Where MAJ:MIN is mounted (first match), from our namespace's mountinfo.
mounted_at() {
    while read -r _ _ mm _ mp _; do
        [ "$mm" = "$1" ] && { echo "$mp"; return; }
    done < /proc/self/mountinfo
}

# MAJ:MIN of whatever is mounted at a path (the topmost mount wins).
source_of() {
    src=
    while read -r _ _ mm _ mp _; do
        [ "$mp" = "$1" ] && src=$mm
    done < /proc/self/mountinfo
    echo "$src"
}

blkid_tag() {
    blkid "$1" 2>/dev/null | sed -n "s/.* $2=\"\([^\"]*\)\".*/\1/p"
}

# Filesystem type; falls back to checking the ext superblock magic (0xEF53 at 1080).
fs_type() {
    t=$(blkid_tag "$1" TYPE)
    if [ -z "$t" ]; then
        magic=$(dd if="$1" bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 | tr -d ' \n')
        [ "$magic" = "53ef" ] && t=ext
    fi
    echo "${t:--}"
}

is_ext() {
    case "$(fs_type "$1")" in ext*) return 0 ;; esac
    return 1
}

cmd_list() {
    for disk in $(usb_disks); do
        for part in $(disk_parts "$disk"); do
            node=$(dev_node "$part") || continue
            # Sizes stay in 512-byte sectors: mksh arithmetic is 32-bit.
            sectors=$(cat "/sys/class/block/$part/size" 2>/dev/null)
            label=$(blkid_tag "$node" LABEL | tr '|' '_')
            echo "$node|${sectors:-0}|$(fs_type "$node")|$label|$(mounted_at "$(majmin "$node")")"
        done
    done
}

cmd_wait() {
    secs=${1:-15}
    i=0
    while [ "$i" -lt "$secs" ]; do
        for disk in $(usb_disks); do
            for part in $(disk_parts "$disk"); do
                node=$(dev_node "$part") && is_ext "$node" && { echo "$node"; return 0; }
            done
        done
        sleep 1
        i=$((i + 1))
    done
    die "no USB ext partition appeared within ${secs}s"
}

# Unique per connection: the kernel gives each newly attached disk a new
# diskseq, even when it reuses the same name and MAJ:MIN.
diskseq_of() {
    d=$(readlink -f "/sys/dev/block/$1") || return 1
    [ -e "$d/partition" ] && d=${d%/*}
    cat "$d/diskseq" 2>/dev/null
}

state_file() {
    echo "$STATE_DIR/$(echo "$1" | tr / _)"
}

# Remember which connection of MAJ:MIN is mounted at MNT.
record_mount() {
    mkdir -p "$STATE_DIR" && echo "$2 $(diskseq_of "$2")" > "$(state_file "$1")"
}

# Is MNT mounted from a drive that has since been unplugged? When the drive is
# plugged back in it usually gets the same MAJ:MIN, so compare diskseq too.
is_stale() {
    cur=$(source_of "$1")
    [ -n "$cur" ] || return 1
    [ -e "/sys/dev/block/$cur" ] || return 0
    f=$(state_file "$1")
    [ -f "$f" ] || return 1
    read -r rec_mm rec_seq < "$f"
    [ "$rec_mm" = "$cur" ] && [ -n "$rec_seq" ] && [ "$rec_seq" != "$(diskseq_of "$cur")" ]
}

# Lazily detach a dead mount at MNT and its /sdcard binds.
detach_stale() {
    old=$(source_of "$1")
    for mp in $(exposed_of "$old"); do
        umount -l "$mp"
    done
    umount -l "$1" || die "could not detach stale mount at $1"
    rm -f "$(state_file "$1")"
}

# Lazily detach /sdcard binds whose drive is gone. These are left behind when a
# drive is pulled while mounted and the main mount is cleaned up without them.
detach_orphans() {
    orphans=
    while read -r _ _ m _ mp _; do
        case "$mp" in "$MEDIA_ROOT"/*) [ -e "/sys/dev/block/$m" ] || orphans="$orphans $mp" ;; esac
    done < /proc/self/mountinfo
    for mp in $orphans; do
        echo "I: detaching $mp (its drive was unplugged)"
        umount -l "$mp"
    done
}

cmd_cleanup() {
    detach_orphans
    is_stale "$1" || return 0
    echo "I: the drive at $1 was unplugged while mounted; detaching it"
    detach_stale "$1"
    echo "OK: cleaned up $1"
}

cmd_mount() {
    dev=$1 mnt=$2 opts=$3 fsck=$4 expose=$5 scan=$6
    [ -b "$dev" ] || die "not a block device: $dev"
    case "$mnt" in /?*) ;; *) die "mount point must be an absolute path: $mnt" ;; esac
    is_ext "$dev" || echo "W: $dev does not look like ext2/3/4 ($(fs_type "$dev")); trying anyway"

    mm=$(majmin "$dev")
    [ -n "$mm" ] || die "cannot find $dev in /sys/class/block"
    detach_orphans
    cur=$(source_of "$mnt")
    if [ -n "$cur" ] && is_stale "$mnt"; then
        # Left over from a drive that was pulled without unmounting.
        echo "I: detaching stale mount at $mnt (the drive was unplugged)"
        detach_stale "$mnt"
        cur=
    fi
    if [ -n "$cur" ]; then
        [ "$cur" = "$mm" ] || die "$mnt is already in use by device $cur"
        echo "OK: $dev is already mounted at $mnt"
        # Mounts made before connections were recorded count as live.
        [ -f "$(state_file "$mnt")" ] || record_mount "$mnt" "$mm"
        [ -n "$expose" ] && cmd_expose "$mnt" "$expose" "$scan"
        return 0
    fi

    other=$(mounted_at "$mm")
    [ -n "$other" ] && echo "W: $dev is also mounted at $other"

    if [ "$fsck" = 1 ]; then
        if command -v e2fsck >/dev/null 2>&1; then
            echo "I: e2fsck -p $dev"
            e2fsck -p "$dev"
            rc=$?
            # 0 = clean, 1 = errors fixed, 2 = fixed but reboot advised; >= 4 = unfixed errors.
            [ "$rc" -ge 4 ] && die "e2fsck exit code $rc; run 'e2fsck -f $dev' manually"
        else
            echo "W: e2fsck not found, skipping check"
        fi
    fi

    if [ ! -d "$mnt" ]; then
        mkdir -p "$mnt" || die "cannot create $mnt"
        chmod 0755 "$mnt"
    fi

    # Files from a PC carry no SELinux label; defcontext gives them the media
    # label (without writing to the drive) so MediaProvider can read them.
    labelled=$opts
    case "$opts" in *context=*) ;; *) [ -n "$expose" ] && labelled="${opts:+$opts,}defcontext=$MEDIA_CONTEXT" ;; esac
    if [ -n "$labelled" ] && mount -t ext4 -o "$labelled" "$dev" "$mnt"; then
        :
    elif [ "$labelled" != "$opts" ]; then
        echo "W: mount with defcontext was refused; apps may not be able to read /sdcard/$expose"
        mount -t ext4 ${opts:+-o "$opts"} "$dev" "$mnt" || die "mount failed (see dmesg)"
    elif [ -n "$opts" ]; then
        die "mount failed (see dmesg)"
    else
        mount -t ext4 "$dev" "$mnt" || die "mount failed (see dmesg)"
    fi
    echo "OK: mounted $dev at $mnt"
    record_mount "$mnt" "$mm"
    [ -n "$expose" ] && cmd_expose "$mnt" "$expose" "$scan"
    cmd_status "$mnt"
}

# Bind MNT into MediaProvider's backing directory so apps see it as /sdcard/NAME.
cmd_expose() {
    mnt=$1 name=$2 scan=$3
    case "$name" in */*|.|..|"") die "invalid /sdcard folder name: $name" ;; esac
    relabel "$mnt"
    target=$MEDIA_ROOT/$name
    mm=$(source_of "$mnt")
    cur=$(source_of "$target")
    if [ -n "$cur" ]; then
        [ "$cur" = "$mm" ] && { echo "OK: already visible at /sdcard/$name"; return 0; }
        die "/sdcard/$name is already a mount of device $cur"
    fi
    if [ ! -d "$target" ]; then
        # Match the ownership and label MediaProvider gives its own folders.
        mkdir "$target" || die "cannot create $target"
        chown "$(stat -c %u:%g "$MEDIA_ROOT/Download")" "$target"
        chmod 2770 "$target"
        chcon "$(stat -c %C "$MEDIA_ROOT/Download")" "$target"
    fi
    mount --bind "$mnt" "$target" || die "bind to $target failed"
    echo "OK: visible to apps at /sdcard/$name"
    # Off by default: indexing a drive with many files ties up MediaProvider,
    # which also serves /sdcard, and apps querying it can freeze.
    [ "$scan" = 1 ] && rescan
    return 0
}

# defcontext only covers files with no SELinux label. Files written by a desktop
# with SELinux (Fedora labels them unlabeled_t) or by an earlier mount without
# defcontext carry their own label, which MediaProvider may not read, so they
# are missing from /sdcard. Give them the media label.
relabel() {
    n=$(find "$1" -xdev ! -context "$MEDIA_CONTEXT" -print 2>/dev/null | wc -l)
    [ "$n" -gt 0 ] || return 0
    find "$1" -xdev ! -context "$MEDIA_CONTEXT" -exec chcon -h "$MEDIA_CONTEXT" {} + 2>/dev/null
    echo "I: gave the media label to $n file(s) with a foreign SELinux label"
}

# Ask MediaProvider to re-index shared storage in the background, so media apps
# pick up (or forget) the drive's files.
rescan() {
    (content call --uri content://media/none --method scan_volume --arg external_primary >/dev/null 2>&1 &)
    echo "I: media rescan started"
}

# Mount points of MAJ:MIN under MediaProvider's directory (our /sdcard binds).
exposed_of() {
    while read -r _ _ m _ mp _; do
        [ "$m" = "$1" ] || continue
        case "$mp" in "$MEDIA_ROOT"/*) echo "$mp" ;; esac
    done < /proc/self/mountinfo
}

# Unmount the /sdcard binds, then MNT. The copies under /mnt/pass_through follow the binds.
umount_all() {
    for mp in $(exposed_of "$mm"); do
        umount ${1:+"$1"} "$mp" 2>/dev/null || return 1
    done
    umount ${1:+"$1"} "$mnt" 2>/dev/null
}

# Is PATH on the drive, through any of the paths it is visible at?
on_drive() {
    for pre in $prefixes; do
        case "$1" in "$pre" | "$pre"/*) return 0 ;; esac
    done
    return 1
}

# PIDs with an open file, working directory or mapping on the drive.
holder_pids() {
    # One ls over every process is fast; a readlink per fd takes minutes.
    # shellcheck disable=SC2012
    ls -l /proc/[0-9]*/fd /proc/[0-9]*/cwd 2>/dev/null | while IFS= read -r line; do
        case "$line" in
            /proc/*/fd:) pid=${line#/proc/}; pid=${pid%%/*} ;;
            *" /proc/"*"/cwd -> "*) p=${line#* /proc/}; on_drive "${line#* -> }" && echo "${p%%/*}" ;;
            *" -> "*) on_drive "${line#* -> }" && echo "$pid" ;;
        esac
    done
    for pre in $prefixes; do
        grep -lF " $pre/" /proc/[0-9]*/maps 2>/dev/null | cut -d/ -f3
    done
}

# Package name of a PID (process names look like "com.app" or "com.app:service").
pkg_of() {
    name=$(tr '\0' '\n' < "/proc/$1/cmdline" 2>/dev/null | head -n 1)
    echo "${name%%:*}"
}

# Files apps save through /sdcard are owned by MediaProvider (and media_rw), which
# a desktop can't use. Hand them to DESKTOP_OWNER; the default ACLs on the drive's
# folders keep MediaProvider's access.
give_back() {
    mp_uid=$(stat -c %u "$MEDIA_ROOT/Download")
    n=$(find "$1" -xdev \( -user "$mp_uid" -o -group "$MEDIA_RW_GID" \) -print 2>/dev/null | wc -l)
    [ "$n" -gt 0 ] || return 0
    find "$1" -xdev \( -user "$mp_uid" -o -group "$MEDIA_RW_GID" \) -exec chown -h "$DESKTOP_OWNER" {} + &&
        echo "I: gave $n file(s) created on Android to $DESKTOP_OWNER"
}

cmd_umount() {
    mnt=$1 force=$2 scan=$3
    [ "$force" = lazy ] && force=force
    mm=$(source_of "$mnt")
    [ -n "$mm" ] || { echo "OK: nothing mounted at $mnt"; return 0; }
    exposed=$(exposed_of "$mm")
    prefixes=$mnt
    for mp in $exposed; do
        n=${mp##*/}
        prefixes="$prefixes $mp /storage/emulated/0/$n /mnt/pass_through/0/emulated/0/$n /mnt/user/0/emulated/0/$n /sdcard/$n"
    done
    [ -n "$exposed" ] && give_back "$mnt"
    sync

    # With FUSE-BPF, /sdcard keeps kernel references to every file apps have
    # looked up on the drive; dropping the dentry/inode caches releases them.
    if ! umount_all; then
        echo 2 > /proc/sys/vm/drop_caches
        umount_all || {
            pids=$(holder_pids | sort -u)
            apps=$(for pid in $pids; do pkg_of "$pid"; done | sort -u | tr '\n' ' ')
            [ -n "$apps" ] && echo "I: in use by: $apps"
            [ "$force" = force ] || die "drive is busy${apps:+, in use by: $apps}"

            # Close the apps reading the drive. MediaProvider and system
            # processes only hold files for those apps, so they are left alone.
            for pid in $pids; do
                pkg=$(pkg_of "$pid")
                uid=$(stat -c %u "/proc/$pid" 2>/dev/null) || continue
                case "$pkg" in *.providers.media*) continue ;; esac
                [ "$uid" -ge 10000 ] || continue
                echo "I: closing $pkg"
                am force-stop "$pkg"
            done
            sleep 1
            echo 2 > /proc/sys/vm/drop_caches
            umount_all || {
                echo "W: still busy; detaching. Wait a few seconds before unplugging the drive"
                umount_all -l || die "could not detach $mnt"
            }
        }
    fi
    echo "OK: unmounted $mnt"
    rm -f "$(state_file "$mnt")"
    [ -n "$exposed" ] && [ "$scan" = 1 ] && rescan
    return 0
}

cmd_status() {
    mnt=$1
    if [ -z "$(source_of "$mnt")" ]; then
        echo "I: nothing mounted at $mnt"
        return 0
    fi
    while read -r _ _ _ _ mp opts rest; do
        [ "$mp" = "$mnt" ] || continue
        # Skip optional fields up to the "-" separator: then fstype, source.
        set -- $rest
        while [ $# -gt 0 ] && [ "$1" != "-" ]; do shift; done
        echo "I: $3 on $mp type $2 ($opts)"
    done < /proc/self/mountinfo
    df -h "$mnt" | tail -n 1 | sed 's/^/I: /'
}

case "$1" in
    list) cmd_list ;;
    wait) cmd_wait "$2" ;;
    mount) cmd_mount "$2" "$3" "$4" "$5" "$6" "$7" ;;
    umount) cmd_umount "$2" "$3" "$4" ;;
    cleanup) cmd_cleanup "$2" ;;
    status) cmd_status "$2" ;;
    *) die "usage: $0 list|wait|mount|umount|cleanup|status ..." ;;
esac
