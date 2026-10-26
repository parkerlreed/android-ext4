package com.example.usbext4mount

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.util.concurrent.Executors

/** One line of `usbmount.sh list` output. */
data class Partition(
    val device: String,
    val sectors: Long,
    val type: String,
    val label: String,
    val mountPoint: String,
) {
    val name get() = device.substringAfterLast('/')
    val bytes get() = sectors * 512
    val isExt get() = type.startsWith("ext")
}

/** The `df -h` line for the mount point. */
data class Usage(val size: String, val used: String, val available: String, val percent: Int)

/** A one-shot snackbar. [offerForce] adds a "Force" action that lazily unmounts. */
data class Message(val text: String, val offerForce: Boolean = false)

class MountViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val script = installScript(app)

    var partitions by mutableStateOf<List<Partition>>(emptyList())
        private set
    var selectedDevice by mutableStateOf<String?>(null)
    var usage by mutableStateOf<Usage?>(null)
        private set
    /** Set while a root command runs; describes what is happening. */
    var busy by mutableStateOf<String?>(null)
        private set
    /** Set when the device listing itself failed, usually because root was not granted. */
    var rootError by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<Message?>(null)
        private set
    val log = mutableStateListOf<String>()

    var mountPoint by mutableStateOf(prefs.getString(KEY_MOUNT_POINT, null) ?: DEFAULT_MOUNT_POINT)
        private set
    var options by mutableStateOf(prefs.getString(KEY_OPTIONS, null) ?: DEFAULT_OPTIONS)
        private set
    var fsck by mutableStateOf(prefs.getBoolean(KEY_FSCK, true))
        private set
    var autoMount by mutableStateOf(prefs.getBoolean(KEY_AUTO_MOUNT, false))
        private set
    /** Also bind the drive to /sdcard/[EXPOSE_NAME] so media apps can read it. */
    var expose by mutableStateOf(prefs.getBoolean(KEY_EXPOSE, true))
        private set

    val selected get() = partitions.firstOrNull { it.device == selectedDevice }
    val mounted get() = partitions.firstOrNull { it.mountPoint == mountPoint.trim() }

    init {
        refresh()
    }

    fun updateMountPoint(value: String) {
        mountPoint = value
        prefs.edit().putString(KEY_MOUNT_POINT, value.trim()).apply()
    }

    fun updateOptions(value: String) {
        options = value
        prefs.edit().putString(KEY_OPTIONS, value.trim()).apply()
    }

    fun updateFsck(value: Boolean) {
        fsck = value
        prefs.edit().putBoolean(KEY_FSCK, value).apply()
    }

    fun updateAutoMount(value: Boolean) {
        autoMount = value
        prefs.edit().putBoolean(KEY_AUTO_MOUNT, value).apply()
    }

    fun updateExpose(value: Boolean) {
        expose = value
        prefs.edit().putBoolean(KEY_EXPOSE, value).apply()
    }

    fun messageShown() {
        message = null
    }

    fun refresh() = background("Scanning USB devices") { }

    fun onUsbDetached() = background("Drive removed, rescanning") {
        // The block device lingers for a moment after the USB detach event.
        Thread.sleep(1500)
    }

    fun mountSelected() {
        val partition = selected ?: return
        val target = mountPoint.trim()
        if (!target.startsWith("/")) {
            message = Message("Mount point must be an absolute path")
            return
        }
        val opts = options.trim()
        val check = if (fsck) "1" else "0"
        val exposeAs = if (expose) EXPOSE_NAME else ""
        background("Mounting ${partition.name}") {
            val result = runScript("mount", partition.device, target, opts, check, exposeAs)
            show(if (result.ok) "Mounted at $target" else failure(result, "Mount failed"))
        }
    }

    /** [force] closes apps still reading the drive, then detaches it if it is still busy. */
    fun unmount(force: Boolean = false) {
        val target = mountPoint.trim()
        background(if (force) "Closing apps and unmounting" else "Unmounting") {
            val result = if (force) runScript("umount", target, "force") else runScript("umount", target)
            when {
                result.lines.any { it.startsWith("W: still busy") } ->
                    show("Detached. Wait a few seconds before removing the drive")
                result.ok -> show("Unmounted. Safe to remove the drive")
                force -> show(failure(result, "Force unmount failed"))
                else -> show(failure(result, "Drive is busy"), offerForce = true)
            }
        }
    }

    fun onUsbAttached() {
        // The attach intent and the attach broadcast can both arrive for one plug-in.
        if (busy == WAITING_FOR_DRIVE) return
        if (!autoMount) {
            refresh()
            return
        }
        val target = mountPoint.trim()
        val opts = options.trim()
        val check = if (fsck) "1" else "0"
        val exposeAs = if (expose) EXPOSE_NAME else ""
        background(WAITING_FOR_DRIVE) {
            // The block device shows up a moment after the USB attach event.
            val waited = runScript("wait", "20")
            val device = waited.lines.lastOrNull()?.takeIf { waited.ok }
            if (device != null) {
                val result = runScript("mount", device, target, opts, check, exposeAs)
                show(if (result.ok) "Auto-mounted at $target" else failure(result, "Auto-mount failed"))
            } else {
                show("No ext partition found on the new drive")
            }
        }
    }

    override fun onCleared() {
        worker.shutdown()
    }

    /**
     * Runs [action] on the worker thread, then re-lists partitions and mount
     * status so the UI reflects the result. Callbacks write state on the main thread.
     */
    private fun background(what: String, action: Worker.() -> Unit) {
        busy = what
        val target = mountPoint.trim()
        worker.execute {
            val updates = mutableListOf<() -> Unit>()
            Worker(updates).action()
            val listing = RootShell.run("sh", script.absolutePath, "list")
            val status = RootShell.run("sh", script.absolutePath, "status", target)
            main.post {
                updates.forEach { it() }
                if (listing.ok) {
                    rootError = null
                    partitions = listing.lines.mapNotNull(::parsePartition)
                    if (selected == null) {
                        selectedDevice = (mounted ?: partitions.firstOrNull { it.isExt })?.device
                    }
                } else {
                    rootError = listing.lines.lastOrNull() ?: "su exited with ${listing.exitCode}"
                    partitions = emptyList()
                    listing.lines.forEach(log::add)
                }
                usage = status.lines.firstNotNullOfOrNull { parseUsage(it, target) }
                busy = null
            }
        }
    }

    /** Runs on the worker thread; state changes are queued for the main thread. */
    private inner class Worker(private val updates: MutableList<() -> Unit>) {
        fun show(text: String, offerForce: Boolean = false) {
            updates += { message = Message(text, offerForce) }
        }

        fun runScript(vararg args: String): RootShell.Result {
            val result = RootShell.run("sh", script.absolutePath, *args)
            updates += {
                log += "$ usbmount ${args.joinToString(" ")}"
                log += result.lines
                if (!result.ok) log += "(exit ${result.exitCode})"
            }
            return result
        }

        fun failure(result: RootShell.Result, fallback: String) =
            result.lines.lastOrNull { it.startsWith("E: ") }?.removePrefix("E: ") ?: fallback
    }

    companion object {
        private const val KEY_MOUNT_POINT = "mount_point"
        private const val KEY_OPTIONS = "options"
        private const val KEY_FSCK = "fsck"
        private const val KEY_AUTO_MOUNT = "auto_mount"
        private const val KEY_EXPOSE = "expose"
        const val EXPOSE_NAME = "USB"
        private const val WAITING_FOR_DRIVE = "Waiting for USB drive"
        private const val DEFAULT_MOUNT_POINT = "/data/usb"
        private const val DEFAULT_OPTIONS = "rw,noatime"

        /** Copies the helper script out of the APK so root can execute it. */
        private fun installScript(context: Context): File {
            val file = File(context.filesDir, "usbmount.sh")
            val bytes = context.assets.open("usbmount.sh").use { it.readBytes() }
            if (!file.exists() || !file.readBytes().contentEquals(bytes)) {
                file.writeBytes(bytes)
            }
            return file
        }

        private fun parsePartition(line: String): Partition? {
            val f = line.split('|')
            if (f.size != 5 || !f[0].startsWith("/dev/")) return null
            return Partition(f[0], f[1].toLongOrNull() ?: 0, f[2], f[3], f[4])
        }

        /** Parses "I: <source> <size> <used> <avail> <use%> <mountpoint>". */
        private fun parseUsage(line: String, mountPoint: String): Usage? {
            val f = line.removePrefix("I: ").trim().split(Regex("\\s+"))
            if (f.size != 6 || f[5] != mountPoint || !f[4].endsWith("%")) return null
            val percent = f[4].removeSuffix("%").toIntOrNull() ?: return null
            return Usage(f[1], f[2], f[3], percent)
        }
    }
}
