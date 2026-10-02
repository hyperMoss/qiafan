package com.example.qiafan.fanfou

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal class OriginalPhotoSaver(private val context: Context) {
    fun save(status: FanfouStatus) {
        val url = status.originalPhoto ?: error("这条动态没有原图")
        saveOne(status.id, url)
    }

    private fun saveOne(id: String, rawUrl: String) {
        val safeUrl = FanfouApi.secureMediaUrl(rawUrl) ?: error("无可用的 HTTPS 原图地址")
        val extension = when (URL(safeUrl).path.substringAfterLast('.').lowercase()) {
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
        val mime = when (extension) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val filename = "fanfou_" + safeId + "." + extension
        val connection = URL(safeUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        try {
            if (connection.responseCode !in 200..299) error("原图下载失败")
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/恰饭")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建相册文件")
                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        connection.inputStream.use { input -> input.copyTo(output) }
                    } ?: error("无法写入相册文件")
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                } catch (error: Exception) {
                    resolver.delete(uri, null, null)
                    throw error
                }
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "恰饭"
                )
                if (!directory.exists() && !directory.mkdirs()) error("无法创建图片目录")
                val file = File(directory, filename)
                try {
                    FileOutputStream(file).use { output ->
                        connection.inputStream.use { input -> input.copyTo(output) }
                    }
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        } finally {
            connection.disconnect()
        }
    }

}
