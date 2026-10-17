package com.example.qiafan.fanfou

import android.content.Context
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
            AlbumImageWriter(context).write(filename, mime) { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

}
