package com.example.qiafan.fanfou

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit

internal class StatusImageSharer(private val context: Context) {
    private val directory = File(context.cacheDir, "content-images")

    fun prepare(image: Bitmap): Intent {
        if (!directory.exists() && !directory.mkdirs()) error("无法创建分享图片")
        val expiredBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiredBefore }?.forEach { it.delete() }
        val file = File.createTempFile("qiafan_content_", ".png", directory)
        try {
            file.outputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码分享图片" }
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".content-images", file)
            return Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("饭否内容图片", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun clearCache() {
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
}
