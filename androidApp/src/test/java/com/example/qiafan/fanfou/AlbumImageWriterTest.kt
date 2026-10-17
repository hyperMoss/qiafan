package com.example.qiafan.fanfou

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumImageWriterTest {
    @Test @Config(sdk = [28])
    fun legacyAndroidWritesAnActualPngInThePicturesAlbum() {
        val context = RuntimeEnvironment.getApplication()
        val filename = "content-${System.nanoTime()}.png"
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "恰饭/$filename")
        try {
            val image = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            AlbumImageWriter(context).write(filename, "image/png") {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val saved = BitmapFactory.decodeFile(file.absolutePath)
            assertEquals(900, saved.width)
            assertEquals(1100, saved.height)
            assertEquals(Color.GREEN, saved.getPixel(450, 550))
        } finally {
            file.delete()
        }
    }

    @Test fun pngIsPublishedOnlyAfterItsFullResolutionContentIsWritten() {
        val context = RuntimeEnvironment.getApplication()
        val provider = AlbumProvider(File(context.cacheDir, "saved-content.png"))
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context.contentResolver).registerOutputStreamSupplier(provider.uri) { provider.file.outputStream() }
        val image = Bitmap.createBitmap(900, 1700, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        AlbumImageWriter(context).write("content.png", "image/png") {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertEquals("content.png", provider.inserted?.getAsString(MediaStore.Images.Media.DISPLAY_NAME))
        assertEquals("Pictures/恰饭", provider.inserted?.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        assertEquals(1, provider.inserted?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updated?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertFalse(provider.deleted)
        val saved = BitmapFactory.decodeFile(provider.file.absolutePath)
        assertEquals(900, saved.width)
        assertEquals(1700, saved.height)
        assertEquals(Color.BLUE, saved.getPixel(450, 850))
    }

    @Test fun incompleteAlbumEntryIsRemovedAfterWriteFailure() {
        val context = RuntimeEnvironment.getApplication()
        val provider = AlbumProvider(File(context.cacheDir, "failed-content.png"))
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context.contentResolver).registerOutputStreamSupplier(provider.uri) { provider.file.outputStream() }
        val result = runCatching {
            AlbumImageWriter(context).write("content.png", "image/png") {
                it.write(byteArrayOf(1, 2, 3))
                error("存储空间不足")
            }
        }
        assertEquals("存储空间不足", result.exceptionOrNull()?.message)
        assertTrue(provider.deleted)
        assertNull(provider.updated)
        assertFalse(provider.file.exists())
    }

    private class AlbumProvider(val file: File) : ContentProvider() {
        val uri: Uri = Uri.parse("content://media/external/images/media/1")
        var inserted: ContentValues? = null
        var updated: ContentValues? = null
        var deleted = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = values?.let { ContentValues(it) }
            return this.uri
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            updated = values?.let { ContentValues(it) }
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deleted = true
            file.delete()
            return 1
        }
    }
}
