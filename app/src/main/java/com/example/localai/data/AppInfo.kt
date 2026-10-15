package com.example.localai.data

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.InputStream

/**
 * Facts about the installed app and this device, read from Android APIs at runtime (nothing is
 * hardcoded, nothing is sent anywhere). Null fields could not be read.
 */
data class AppInfo(
    val appName: String,
    val versionName: String?,
    val versionCode: Long?,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    /** ABI of the native libraries the system installed for this app, e.g. arm64-v8a. */
    val appAbi: String?,
    val supportedAbis: List<String>
) {
    companion object {
        fun read(context: Context): AppInfo {
            val app = context.applicationContext
            val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
            return AppInfo(
                appName = app.applicationInfo.loadLabel(app.packageManager).toString(),
                versionName = pkg?.versionName,
                versionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) },
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                appAbi = abiFromNativeLibraryDir(app.applicationInfo.nativeLibraryDir),
                supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
            )
        }

        /** The installer puts libraries in .../lib/<isa>; map the ISA folder to the ABI name. */
        fun abiFromNativeLibraryDir(dir: String?): String? = when (dir?.let { File(it).name }) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            null, "" -> null
            else -> File(dir).name
        }
    }
}

/** The third-party notices bundled in the APK (assets/licenses/THIRD_PARTY_NOTICES.txt). */
object LicenceNotices {
    const val ASSET_PATH = "licenses/THIRD_PARTY_NOTICES.txt"

    /** Reads the complete text, unmodified; a missing or unreadable asset is a failure, never a placeholder. */
    fun load(open: () -> InputStream): Result<String> = runCatching {
        open().use { it.readBytes().toString(Charsets.UTF_8) }
    }.mapCatching { text ->
        require(text.isNotBlank()) { "The licence file is empty." }
        text
    }

    fun load(context: Context): Result<String> = load { context.assets.open(ASSET_PATH) }
}
