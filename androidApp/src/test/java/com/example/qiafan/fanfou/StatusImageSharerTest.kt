package com.example.qiafan.fanfou

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusImageSharerTest {
    @Before fun resetProviderPaths() = resetContentImageProviderPaths()

    @Test fun shareFilesAreReadableFullSizeAndNeverOverwriteEarlierShares() {
        val context = RuntimeEnvironment.getApplication()
        val sharer = StatusImageSharer(context)
        try {
            val image = Bitmap.createBitmap(900, 1500, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.RED)
            val first = sharer.prepare(image).getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            image.eraseColor(Color.BLUE)
            val second = sharer.prepare(image).getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertNotEquals(first, second)
            for ((uri, expected) in listOf(first to Color.RED, second to Color.BLUE)) {
                assertEquals("${context.packageName}.content-images", uri.authority)
                val saved = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                assertEquals(900, saved.width)
                assertEquals(1500, saved.height)
                assertEquals(expected, saved.getPixel(450, 750))
            }
        } finally {
            sharer.clearCache()
        }
    }

    @Test fun sharingExpiresOldFilesAndTheProviderCannotExposeOtherCacheFiles() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "content-images").apply { mkdirs() }
        val old = File(directory, "old.png").apply {
            writeText("expired")
            setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))
        }
        val privateFile = File(context.cacheDir, "private-session.txt").apply { writeText("local fixture") }
        val sharer = StatusImageSharer(context)
        try {
            sharer.prepare(Bitmap.createBitmap(900, 500, Bitmap.Config.ARGB_8888))
            assertFalse(old.exists())
            val result = runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".content-images", privateFile)
            }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            val provider = context.packageManager.resolveContentProvider(context.packageName + ".content-images", 0)!!
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
            sharer.clearCache()
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(privateFile.exists())
        } finally {
            sharer.clearCache()
            privateFile.delete()
        }
    }
}

// Robolectric 每条测试使用独立缓存目录，AndroidX 的静态路径缓存需要同步重置。
internal fun resetContentImageProviderPaths() {
    val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
    (field.get(null) as MutableMap<*, *>).clear()
}
