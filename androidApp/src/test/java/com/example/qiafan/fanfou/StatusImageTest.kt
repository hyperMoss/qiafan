package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusImageTest {
    private val user = FanfouUser("rice", "一碗饭", "", false)
    private val status = FanfouStatus(
        "test-status", "今天好好吃饭，也记下沿途的风景。\n长按动态，留下这一刻。", "Sat Oct 17 10:00:00 +0800 2026",
        user, false, null, null, null
    )
    private fun reply(id: String, text: String = "这是一条自动加入图片的评论。") = status.copy(
        id = id, text = text, user = user.copy(id = id, name = "饭友 $id"), replyToStatusId = status.id
    )

    @Test fun commentsAddContentAndPhotoAspectRatioIsPreserved() {
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val base = StatusImageRenderer.render(status, emptyList()) { _, _ -> error("不应加载图片") }
        val image = StatusImageRenderer.render(status.copy(photo = "photo"), listOf(reply("one"))) { _, _ -> photo }
        assertEquals(900, image.width)
        assertTrue(image.height > base.height + 350)
        val redRows = (0 until image.height).count { image.getPixel(450, it) == Color.RED }
        val firstRedRow = (0 until image.height).first { image.getPixel(450, it) == Color.RED }
        val redColumns = (0 until image.width).count { image.getPixel(it, firstRedRow) == Color.RED }
        assertEquals(redColumns, redRows * 2)
        assertEquals(Color.rgb(247, 244, 236), image.getPixel(0, 0))
        assertEquals(Color.rgb(255, 253, 247), image.getPixel(50, 50))
        for (x in listOf(20, 32, image.width - 32, image.width - 20)) {
            assertNotEquals(image.getPixel(0, 200), image.getPixel(x, 200))
            assertEquals(image.getPixel(x, 200), image.getPixel(x, image.height - 100))
        }
    }

    @Test fun textWrapsWithoutTruncatingLongContent() {
        val short = StatusImageRenderer.render(status, emptyList()) { _, _ -> null }
        val long = StatusImageRenderer.render(status.copy(text = "饭否内容 English 你好 🍚\n".repeat(60)), emptyList()) { _, _ -> null }
        assertTrue(long.height > short.height * 3)
    }

    @Test fun missingPhotoFailsInsteadOfSilentlyDroppingIt() {
        val error = runCatching {
            StatusImageRenderer.render(status.copy(photo = "missing"), emptyList()) { _, _ -> null }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("配图加载失败"))
    }

    @Test fun excessiveCommentsFailWithAnActionableMessage() {
        val error = runCatching {
            StatusImageRenderer.render(status, (1..200).map { reply("$it") }) { _, _ -> null }
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("无法生成完整图片"))
    }

    @Test fun allVisibleDirectRepliesAreIncludedWithoutASelectionPanel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val replies = listOf(reply("one"), reply("two"))
        var closed = false
        val dialog = StatusImageDialog(activity, status,
            { replies + replies[0] + reply("other").copy(replyToStatusId = "another-status") },
            { it(true) }, { closed = true })
        try {
            dialog.open()
            await { ready(dialog) }
            assertTrue(preview(dialog).contentDescription.toString().contains("2 条评论"))
            val actual = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap
            val expected = StatusImageRenderer.render(status, replies) { _, _ -> null }
            assertTrue(expected.sameAs(actual))
            assertTrue(nodes(dialog.window!!.decorView).none { it is CheckBox })
            assertEquals(3, nodes(dialog.window!!.decorView).filterIsInstance<ImageButton>().size)
            assertTrue(action(dialog, "分享").isEnabled)
            val decor = dialog.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(860, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 420, 860)
            val screenshot = Bitmap.createBitmap(420, 860, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(screenshot))
            val screenshotFile = File("build/reports/status-image-dialog-sample.png")
            screenshotFile.parentFile?.mkdirs()
            screenshotFile.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            action(dialog, "返回").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertTrue(closed)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun failedCommentLoadBlocksExportAndRetryIncludesAllComments() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var attempts = 0
        val dialog = StatusImageDialog(controller.get(), status,
            { if (++attempts == 1) error("网络不可用") else listOf(reply("one"), reply("two")) }, { it(true) }, {})
        try {
            dialog.open()
            await { nodes(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("评论加载失败") } }
            assertFalse(action(dialog, "保存到本地").isEnabled)
            assertFalse(action(dialog, "分享").isEnabled)
            nodes(dialog.window!!.decorView).filterIsInstance<Button>().single { it.text == "重试" }.performClick()
            await { ready(dialog) }
            assertEquals(2, attempts)
            assertTrue(preview(dialog).contentDescription.toString().contains("2 条评论"))
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun exportsStayDisabledUntilCommentsFinishLoading() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val release = java.util.concurrent.CountDownLatch(1)
        val dialog = StatusImageDialog(controller.get(), status,
            { release.await(); listOf(reply("one")) }, { it(true) }, {})
        try {
            dialog.open()
            assertFalse(action(dialog, "保存到本地").isEnabled)
            assertFalse(action(dialog, "分享").isEnabled)
            release.countDown()
            await { ready(dialog) }
            assertTrue(preview(dialog).contentDescription.toString().contains("1 条评论"))
        } finally {
            release.countDown()
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun permissionDenialLeavesPreviewAndSaveRetryAvailable() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val dialog = StatusImageDialog(controller.get(), status, { emptyList() }, { it(false) }, {})
        try {
            dialog.open()
            await { ready(dialog) }
            action(dialog, "保存到本地").performClick()
            assertTrue(nodes(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("未获得相册写入权限") })
            assertTrue(ready(dialog))
            assertNotNull(preview(dialog).drawable)
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun sharingUsesTheCurrentPreviewWithoutRequestingAlbumPermission() {
        resetContentImageProviderPaths()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var permissionRequested = false
        val dialog = StatusImageDialog(activity, status, { listOf(reply("one"), reply("two")) },
            { permissionRequested = true; it(false) }, {})
        try {
            dialog.open()
            await { ready(dialog) }
            assertTrue(preview(dialog).contentDescription.toString().contains("2 条评论"))
            chooseStyle(dialog, StatusImageStyle.MIDNIGHT)
            await { ready(dialog) }
            val actualPreview = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap
            val previewHeight = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap.height
            action(dialog, "分享").performClick()
            var chooser: Intent? = null
            await {
                chooser = shadowOf(activity).peekNextStartedActivity()
                chooser != null
            }
            assertEquals(Intent.ACTION_CHOOSER, chooser!!.action)
            val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("image/png", send.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            val image = activity.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(900, image.width)
            assertEquals(previewHeight, image.height)
            assertTrue(actualPreview.sameAs(image))
            assertFalse(permissionRequested)
            assertTrue(ready(dialog))
            assertTrue(action(dialog, "分享").isEnabled)
        } finally {
            dialog.dismiss()
            StatusImageSharer(activity).clearCache()
            controller.pause().stop().destroy()
        }
    }

    @Test fun switchingStylesKeepsAllCommentsWithoutReloadingAndRemembersTheChoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var loads = 0
        val replies = listOf(reply("one"), reply("two"))
        val dialog = StatusImageDialog(activity, status, { loads++; replies }, { it(true) }, {})
        try {
            dialog.open()
            await { ready(dialog) }
            val initial = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap
            for (style in listOf(StatusImageStyle.MINIMAL, StatusImageStyle.MIDNIGHT, StatusImageStyle.PAPER, StatusImageStyle.MIDNIGHT)) {
                chooseStyle(dialog, style)
                await { ready(dialog) }
                val image = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap
                assertEquals(style.background, image.getPixel(0, 0))
                assertTrue(preview(dialog).contentDescription.toString().contains("2 条评论"))
                assertEquals(initial.height, image.height)
                assertEquals(1, loads)
            }
            dialog.dismiss()
            val reopened = StatusImageDialog(activity, status, { replies }, { it(true) }, {})
            try {
                reopened.open()
                await { ready(reopened) }
                assertEquals(StatusImageStyle.MIDNIGHT.background,
                    (preview(reopened).drawable as android.graphics.drawable.BitmapDrawable).bitmap.getPixel(0, 0))
            } finally { reopened.dismiss() }
        } finally {
            dialog.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun eachStyleUsesTheOriginalPhotoAndExportsEveryComment() {
        val requested = mutableListOf<String>()
        val photo = Bitmap.createBitmap(756, 420, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val images = StatusImageStyle.entries.map { style ->
            val image = StatusImageRenderer.render(status.copy(photo = "small-preview", originalPhoto = "original-photo"),
                listOf(reply("one"), reply("two")), style) { url, _ -> requested += url; photo }
            assertEquals(900, image.width)
            assertEquals(style.background, image.getPixel(0, 0))
            val file = File("build/reports/share-styles/${style.name.lowercase()}.png")
            file.parentFile?.mkdirs()
            val sample = StatusImageRenderer.render(status, listOf(reply("one"), reply("two")), style) { _, _ -> null }
            file.outputStream().use { sample.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image
        }
        assertEquals(listOf("original-photo", "original-photo", "original-photo"), requested)
        assertEquals(1, images.map { it.height }.distinct().size)
        assertFalse(images[0].sameAs(images[1]))
        assertFalse(images[1].sameAs(images[2]))
    }

    @Test fun renderPreviewArtifact() {
        val photo = Bitmap.createBitmap(804, 460, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(227, 239, 230))
        }
        val image = StatusImageRenderer.render(status.copy(photo = "local-test-image"), listOf(
            reply("friend", "吃饱了才有力气看世界。🍚"), reply("walker", "周末一起去散步吧！\n记录生活里的小事。")
        )) { _, _ -> photo }
        val file = File("build/reports/status-image-sample.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(file.length() > 0)
    }

    @Test fun contentBlockOriginalIsLoadedOnceAndReusedWhenSwitchingStyles() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        var originals = 0
        val shared = status.copy(text = "我的感想 转@朋友 引用中的原文", repostId = "source")
        val source = status.copy(id = "source", text = "完整原文，在分享图片中保留。", user = user.copy(id = "source-author", name = "朋友"))
        val replies = listOf(reply("one"), reply("two"))
        val dialog = StatusImageDialog(controller.get(), shared, { replies }, { it(true) }, {},
            loadOriginal = { id -> originals++; assertEquals(source.id, id); source })
        try {
            dialog.open()
            await { ready(dialog) }
            for (style in listOf(StatusImageStyle.MINIMAL, StatusImageStyle.MIDNIGHT)) {
                chooseStyle(dialog, style)
                await { ready(dialog) }
                val actual = (preview(dialog).drawable as android.graphics.drawable.BitmapDrawable).bitmap
                val expected = StatusImageRenderer.render(shared, replies, style, mapOf(source.id to source)) { _, _ -> null }
                assertTrue(expected.sameAs(actual))
                assertEquals(1, originals)
                assertTrue(preview(dialog).contentDescription.toString().contains("2 条评论"))
            }
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    @Test fun inaccessibleOriginalFallsBackToEmbeddedContentWithoutBlockingExport() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val shared = status.copy(text = "转@朋友 原动态暂不可读，仍保留已有转发原文。", repostId = "source")
        val dialog = StatusImageDialog(controller.get(), shared, { emptyList() }, { it(true) }, {},
            loadOriginal = { throw FanfouApiException(403, "无权访问") })
        try {
            dialog.open()
            await { ready(dialog) }
            assertTrue(action(dialog, "分享").isEnabled)
            assertTrue(action(dialog, "保存到本地").isEnabled)
        } finally { dialog.dismiss(); controller.pause().stop().destroy() }
    }

    private fun chooseStyle(dialog: StatusImageDialog, style: StatusImageStyle) {
        nodes(dialog.window!!.decorView).single {
            it.contentDescription?.toString()?.startsWith("切换分享图片风格") == true
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val picker = ShadowDialog.getLatestDialog()
        if (style == StatusImageStyle.MINIMAL) {
            val decor = picker.window!!.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 420, 520)
            val image = Bitmap.createBitmap(420, 520, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(image))
            val file = File("build/reports/share-styles/picker.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        nodes(picker.window!!.decorView).single { it.contentDescription == "选择${style.title}风格" }.performClick()
    }

    private fun preview(dialog: StatusImageDialog) = nodes(dialog.window!!.decorView).filterIsInstance<ImageView>()
        .single { it.contentDescription?.toString()?.startsWith("内容图片预览") == true }
    private fun action(dialog: StatusImageDialog, description: String) = nodes(dialog.window!!.decorView)
        .single { it.contentDescription?.toString() == description }
    private fun ready(dialog: StatusImageDialog) = action(dialog, "保存到本地").isEnabled
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) }
    } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("等待图片生成或评论读取超时")
    }
}
