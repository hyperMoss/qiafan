package com.example.qiafan.fanfou

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 原图与内容长图共用的相册写入边界；失败时移除未完成的文件。 */
internal class AlbumImageWriter(private val context: Context) {
    fun write(filename: String, mime: String, writeContent: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/恰饭")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }) ?: error("无法创建相册文件")
            try {
                resolver.openOutputStream(uri)?.use(writeContent) ?: error("无法写入相册文件")
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null) > 0) { "无法完成相册写入" }
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "恰饭"
            )
            if (!directory.exists() && !directory.mkdirs()) error("无法创建图片目录")
            val file = File(directory, filename)
            try {
                FileOutputStream(file).use(writeContent)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }
    }
}
