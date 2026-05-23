package com.wyspr.feature.onboarding.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds an [Intent.ACTION_SEND] intent that hands off the running app's
 * APK to any installed sharing target — Gmail, Signal, Telegram, Quick
 * Share, Bluetooth, Drive, whatever the user has.
 *
 * Unlike [ApkShareServer] (which serves the APK over LAN HTTPS and the
 * peer-update flow verifies SHA-256 before install), this path has NO
 * built-in verification gate on the recipient side — they receive an
 * APK attachment via whatever channel was chosen. To preserve some
 * trust anchor, the SHA-256 fingerprint is embedded in [Intent.EXTRA_TEXT]
 * so the recipient can compare it against a value the sender quotes
 * out-of-band (or against the value shown on this screen).
 *
 * The running APK at [Context.getApplicationInfo].sourceDir lives under
 * `/data/app/...` and is not grant-able by [FileProvider]; we copy it
 * once per share into the `share-out` cache subdir declared in
 * `file_paths.xml` and mint a content URI from there.
 */
object ApkShareIntent {

    /**
     * Stages the APK and returns a SEND intent ready to wrap in
     * [Intent.createChooser]. Caller is responsible for showing the
     * chooser and handling any [android.content.ActivityNotFoundException]
     * (extremely unlikely — every Android device has at least one
     * SEND-capable app).
     *
     * Re-copies the APK each invocation: the running APK changes after
     * an in-place update, and recipients should always get THIS build.
     */
    suspend fun create(context: Context): Intent = withContext(Dispatchers.IO) {
        val versionName = resolveVersionName(context)
        val shareDir = File(context.cacheDir, "share-out").apply { mkdirs() }
        val staged = File(shareDir, "wyspr-$versionName.apk")
        val source = File(context.applicationInfo.sourceDir)
        source.inputStream().use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        val sha256 = stagedSha256(staged)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            staged,
        )
        val prettyHash = sha256.chunked(8).joinToString(" ")
        Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Wyspr $versionName")
            putExtra(
                Intent.EXTRA_TEXT,
                "Wyspr $versionName — private, censorship-resistant " +
                    "messaging.\n\nVerify the APK matches this SHA-256 " +
                    "before installing:\n$prettyHash",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun resolveVersionName(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

    private fun stagedSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
