package com.example.qiafan.fanfou

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusImageContentBlockTest {
    private val author = FanfouUser("rice", "一碗饭", "", false)
    private val original = FanfouStatus("original", "周末去河边散步，路上看见了很漂亮的晚霞。\n把喜欢的日常慢慢记下来。", "Sun Oct 25 17:20:00 +0800 2026",
        FanfouUser("walker", "散步的人", "", false), false, null, null, null)
    private val repost = FanfouStatus("shared", "这样的日常真好。 转@散步的人 周末去河边散步，路上看见了很漂亮的晚霞。", "Sun Oct 25 17:30:00 +0800 2026", author, false, null, null, original.id)
    private val comments = listOf(
        repost.copy(id = "reply-1", text = "@一碗饭 周末也要出去走走。", repostId = null, replyToStatusId = repost.id,
            user = author.copy(id = "friend", name = "饭友")),
        repost.copy(id = "reply-2", text = "@一碗饭 喜欢这种有阳光、有风的日子。", repostId = null, replyToStatusId = repost.id,
            user = author.copy(id = "sunny", name = "晴天"))
    )

    private fun blockRuns(bitmap: Bitmap, color: Int): Int {
        var runs = 0
        var length = 0
        for (y in 0 until bitmap.height) {
            if (bitmap.getPixel(80, y) == color) length++
            else {
                if (length > 20) runs++
                length = 0
            }
        }
        if (length > 20) runs++
        return runs
    }

    @Test fun repostAndEveryCommentHaveTheirOwnVisibleBlockInAllStyles() {
        val formatted = statusContent(repost)
        assertEquals("这样的日常真好。", formatted.text)
        assertEquals(original.id, formatted.quoteId)
        assertEquals("周末也要出去走走。", statusContent(comments.first()).text)
        for (style in StatusImageStyle.entries) {
            val image = StatusImageRenderer.render(repost, comments, style, mapOf(original.id to original)) { _, _ -> null }
            assertEquals("一块引用原文和两块评论，不重复嵌套主动态", 3, blockRuns(image, style.contentBlock))
            val output = File("build/reports/content-block-shares/${style.name.lowercase()}.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun unavailableOriginalStillExportsEmbeddedTextAsABlock() {
        val image = StatusImageRenderer.render(repost, emptyList(), StatusImageStyle.PAPER) { _, _ -> null }
        assertEquals(1, blockRuns(image, StatusImageStyle.PAPER.contentBlock))
    }

    @Test fun originalPhotoIsInsideItsBlockAndNotRepeatedOutsideIt() {
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val source = original.copy(photo = "preview", originalPhoto = "original-photo")
        val requested = mutableListOf<String>()
        val image = StatusImageRenderer.render(repost.copy(photo = "preview", originalPhoto = "original-photo"), emptyList(),
            StatusImageStyle.MINIMAL, mapOf(source.id to source)) { url, _ -> requested += url; photo }
        assertEquals(listOf("original-photo"), requested)
        val redRows = (0 until image.height).count { image.getPixel(450, it) == Color.RED }
        assertEquals("引用内可用宽度 708，应以 2:1 原比例绘制一次", 354, redRows)
    }

    @Test fun plainTextAndReplyMetadataShareTheFeedFormatter() {
        val value = original.copy(text = "@一碗饭 很赞！ https://example.com", repostId = null,
            replyToStatusId = repost.id, replyToScreenName = author.name,
            links = listOf(FanfouTextLink(9, 28, "https://example.com")))
        val body = statusContent(value)
        assertEquals("很赞！ https://example.com", body.text)
        assertEquals(repost.id, body.quoteId)
        assertEquals(author.name, body.captionName)
        assertEquals(4, body.links.single().start)
    }

    @Test fun repostChainsExportEveryIntermediateCommentAndTheOriginalPhoto() {
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val root = original.copy(user = original.user.copy(name = "原作者"), photo = "root-photo", originalPhoto = "root-photo")
        val chain = repost.copy(text = "我的评论 转@饭友 上一人的评论 转@原作者 ${root.text}", photo = null, originalPhoto = null)
        for (style in StatusImageStyle.entries) {
            val requested = mutableListOf<String>()
            val image = StatusImageRenderer.render(chain, emptyList(), style, mapOf(root.id to root)) { url, avatar ->
                if (avatar) null else { requested += url; photo }
            }
            assertEquals("原图应在引用区域内保留且只绘制一次", listOf("root-photo"), requested)
            val output = File("build/reports/content-block-shares/chain-${style.name.lowercase()}.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        photo.recycle()
    }
}
