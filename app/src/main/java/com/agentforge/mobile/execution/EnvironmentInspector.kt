package com.agentforge.mobile.execution

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class DeviceExecutionCapabilities(
    val localShellAvailable: Boolean,
    val termuxInstalled: Boolean,
    val termuxRunCommandPermissionGranted: Boolean,
    val javaAvailable: Boolean,
    val javaVersionInfo: String,
    val gradleAvailable: Boolean,
    val androidSdkAvailable: Boolean,
    val gitAvailable: Boolean,
    val summaryNote: String
)

/**
 * Inspects the actual Android device / runtime environment honestly without faking capabilities
 * or attempting root access.
 *
 * Checks:
 * 1. Standard non-root `/system/bin/sh` availability inside the app sandbox
 * 2. Termux (`com.termux`) package installation and `com.termux.permission.RUN_COMMAND` grant
 * 3. Availability of `java`, `gradle`, `git`, and Android SDK build tools on PATH
 */
class EnvironmentInspector(private val context: Context? = null) {

    fun inspectCapabilities(workingDir: File? = null): DeviceExecutionCapabilities {
        val shFile = File("/system/bin/sh").takeIf { it.exists() } ?: File("/bin/sh")
        val localShell = shFile.exists() && shFile.canExecute()

        val termuxInstalled = context?.let { ctx ->
            runCatching {
                ctx.packageManager.getPackageInfo("com.termux", 0)
                true
            }.getOrDefault(false)
        } ?: false

        val termuxPermGranted = context?.let { ctx ->
            ctx.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                PackageManager.PERMISSION_GRANTED
        } ?: false

        val javaInfo = probeCommand("java -version", workingDir)
        val gradleInfo = probeCommand("gradle -v", workingDir)
        val gitInfo = probeCommand("git --version", workingDir)
        val hasGradleWrapper = workingDir?.let { File(it, "gradlew").exists() } ?: false

        val androidSdkEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        val androidSdkAvailable = !androidSdkEnv.isNullOrBlank() && File(androidSdkEnv).exists()

        val note = buildString {
            if (gradleInfo.first || (hasGradleWrapper && javaInfo.first)) {
                append("Full local build & test environment detected.")
            } else if (termuxInstalled && termuxPermGranted) {
                append("Termux integration active. Commands can run via Termux environment.")
            } else if (termuxInstalled && !termuxPermGranted) {
                append("Termux is installed, but 'Run commands in Termux environment' permission is not yet granted in Android Settings.")
            } else {
                append("Standard Android sandbox environment: Direct SAF file operations, diffing, patching, and code verification are active. Native APK compilation on-device requires Termux + JDK/Android SDK or an external workstation.")
            }
        }

        return DeviceExecutionCapabilities(
            localShellAvailable = localShell,
            termuxInstalled = termuxInstalled,
            termuxRunCommandPermissionGranted = termuxPermGranted,
            javaAvailable = javaInfo.first,
            javaVersionInfo = javaInfo.second.lines().firstOrNull().orEmpty(),
            gradleAvailable = gradleInfo.first || hasGradleWrapper,
            androidSdkAvailable = androidSdkAvailable,
            gitAvailable = gitInfo.first,
            summaryNote = note
        )
    }

    private fun probeCommand(cmd: String, workingDir: File?): Pair<Boolean, String> {
        return try {
            val pb = ProcessBuilder("sh", "-c", cmd)
            if (workingDir != null && workingDir.exists()) {
                pb.directory(workingDir)
            }
            pb.redirectErrorStream(true)
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            (exit == 0) to out
        } catch (e: Exception) {
            false to (e.message ?: "Not available")
        }
    }
}
