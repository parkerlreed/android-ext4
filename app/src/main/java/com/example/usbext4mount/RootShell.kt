package com.example.usbext4mount

import java.io.IOException

/** Runs commands as root through the `su` binary (Magisk, KernelSU, APatch, ...). */
object RootShell {
    data class Result(val exitCode: Int, val lines: List<String>) {
        val ok get() = exitCode == 0
    }

    fun run(vararg args: String): Result {
        val command = args.joinToString(" ") { quote(it) }
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.outputStream.close()
            val lines = process.inputStream.bufferedReader().readLines()
            Result(process.waitFor(), lines)
        } catch (e: IOException) {
            Result(-1, listOf("E: cannot run su (is the device rooted?): ${e.message}"))
        }
    }

    private fun quote(arg: String) = "'" + arg.replace("'", "'\\''") + "'"
}
