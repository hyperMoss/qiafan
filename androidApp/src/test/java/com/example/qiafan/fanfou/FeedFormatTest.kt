package com.example.qiafan.fanfou

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FeedFormatTest {
    private val author = FanfouUser("author", "作者", "", false)

    private fun card(activity: FanfouActivity, status: FanfouStatus, detail: Boolean = false): View =
        FanfouActivity::class.java.getDeclaredMethod("statusCard", FanfouStatus::class.java, Boolean::class.java)
            .apply { isAccessible = true }.invoke(activity, status, detail) as View

    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())

    private fun withActivity(block: (FanfouActivity) -> Unit) {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }

    @Test fun repostIsSplitIntoCompactQuoteCard() = withActivity { a ->
        val status = FanfouStatus("r1", "转@朋友 今天天气不错", "", author, false, null, null, "orig1")
        val shown = texts(card(a, status))
        assertTrue("转发原文应进引用块", shown.contains("今天天气不错"))
        assertTrue(shown.contains("转自 @朋友"))
        assertFalse("转发原文不应再以裸文本重复展示", shown.contains("转@朋友 今天天气不错"))
        assertFalse("引用卡不应带『查看详情』之类的杂项链接", shown.any { it.contains("查看详情") })
    }

    @Test fun repostKeepsUserWordsBeforeTheMarker() = withActivity { a ->
        val status = FanfouStatus("r2", "说得好 转@朋友 原文内容", "", author, false, null, null, "orig2")
        val shown = texts(card(a, status))
        assertTrue(shown.contains("说得好"))
        assertTrue(shown.contains("原文内容"))
        assertFalse(shown.contains("说得好 转@朋友 原文内容"))
    }

    @Test fun repostWithoutRecognizablePrefixStaysRaw() = withActivity { a ->
        val status = FanfouStatus("r3", "随手转一下", "", author, false, null, null, "orig3")
        val shown = texts(card(a, status))
        assertTrue(shown.contains("随手转一下"))
        assertFalse(shown.any { it.contains("转自 @") })
    }

    @Test fun replyFallsBackToASourceLineWhenQuoteIsUnavailable() = withActivity { a ->
        val status = FanfouStatus("c1", "@朋友 说得对", "", author, false, null, null, null,
            replyToStatusId = "p1", replyToScreenName = "朋友")
        val shown = texts(card(a, status))
        assertTrue("评论正文应剥掉 @ 前缀", shown.contains("说得对"))
        assertTrue("引用拿不到时评论应退回一行来源提示", shown.contains("来自 @朋友"))
        assertFalse("评论正文不应残留裸前缀", shown.contains("@朋友 说得对"))
        assertFalse(shown.any { it.contains("查看原动态") || it.contains("查看详情") })
    }

    @Test fun replyWithoutPrefixFallsBackToServerScreenName() = withActivity { a ->
        val status = FanfouStatus("c2", "这是一条没有前缀的回复", "", author, false, null, null, null,
            replyToStatusId = "p2", replyToScreenName = "朋友")
        val shown = texts(card(a, status))
        assertTrue(shown.contains("这是一条没有前缀的回复"))
        assertTrue(shown.contains("来自 @朋友"))
    }

    @Test fun detailReplyStillCarriesTheQuotedOriginal() = withActivity { a ->
        val parent = FanfouStatus("p3", "被回复的原动态内容", "", author, false, null, null, null)
        FanfouActivity::class.java.getDeclaredField("quoteContext").apply { isAccessible = true }
            .set(a, mutableMapOf("p3" to parent))
        val status = FanfouStatus("c3", "@作者 收到", "", author, false, null, null, null,
            replyToStatusId = "p3", replyToScreenName = "作者")
        val shown = texts(card(a, status, detail = true))
        assertTrue(shown.contains("被回复的原动态内容"))
    }

    @Test fun plainStatusKeepsRawTextWithoutRelationCaption() = withActivity { a ->
        val status = FanfouStatus("s1", "普通动态，没有回复或转发。", "", author, false, null, null, null)
        val shown = texts(card(a, status))
        assertTrue(shown.contains("普通动态，没有回复或转发。"))
        assertFalse(shown.any { it.startsWith("回复") })
        assertFalse(shown.any { it.startsWith("转自") })
        assertFalse(shown.any { it.startsWith("来自") })
    }

    private class FixedCache(private var payload: String?) : NotificationResponseCache {
        var written: String? = null
        override fun readNotification(path: String, owner: FanfouCredentials) = payload
        override fun writeNotification(path: String, payload: String, owner: FanfouCredentials) {
            written = payload
            this.payload = payload
        }
    }

    private fun api(cache: FixedCache): FanfouApi {
        val owner = NofanApplication.credentials(OAuthToken("t", "s"))
        return FanfouApi(cache) { owner }
    }

    @Test fun cachedHomeParsesTheStoredFeedPage() {
        val payload = """[{"id":"10","text":"缓存动态","user":{"id":"author","screen_name":"作者"},"favorited":false}]"""
        val cached = api(FixedCache(payload)).cachedHome()
        assertEquals("缓存动态", cached?.single()?.text)
        assertEquals("作者", cached?.single()?.user?.name)
    }

    @Test fun cachedHomeIgnoresCorruptEmptyOrMissingCache() {
        assertNull(api(FixedCache("invalid")).cachedHome())
        assertNull(api(FixedCache("[]")).cachedHome())
        assertNull(api(FixedCache(null)).cachedHome())
    }

    @Test fun invalidateHomeCacheEmptiesTheStoredPage() {
        val cache = FixedCache("""[{"id":"10","text":"缓存动态","user":{"id":"author"},"favorited":false}]""")
        val client = api(cache)
        assertNotNull(client.cachedHome())
        client.invalidateHomeCache()
        assertEquals("[]", cache.written)
        assertNull(client.cachedHome())
    }
}
