package dev.amenhancer.module

import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * 本地配置模式：不依赖 libxposed API 102 remote preferences / remote file。
 * 配置存储在模块私有 SharedPreferences，文件存储在模块 filesDir。
 */
internal class XposedServiceSnapshot private constructor(
    val preferences: SharedPreferences,
    val filesDir: File,
    val status: String,
) {
    val isRemoteAvailable: Boolean get() = true
    val isRemoteFileAvailable: Boolean get() = true

    init {
        require(status.isNotBlank()) { "connection status must not be blank" }
    }

    /** 只读打开已有文件；不存在时返回 null。 */
    internal fun openRemoteFile(name: String): ParcelFileDescriptor? =
        runCatching {
            val file = File(filesDir, name)
            if (!file.exists()) null else ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }.getOrNull()

    /** 覆盖写入文件（自动截断），返回是否成功。 */
    internal fun writeRemoteFile(name: String, bytes: ByteArray): Boolean =
        runCatching {
            File(filesDir, name).writeBytes(bytes)
            true
        }.getOrDefault(false)

    internal fun deleteRemoteFile(name: String): Boolean =
        runCatching { File(filesDir, name).delete() }.getOrDefault(false)

    companion object {
        fun local(preferences: SharedPreferences, filesDir: File): XposedServiceSnapshot =
            XposedServiceSnapshot(
                preferences = preferences,
                filesDir = filesDir,
                status = "本地配置模式",
            )
    }
}