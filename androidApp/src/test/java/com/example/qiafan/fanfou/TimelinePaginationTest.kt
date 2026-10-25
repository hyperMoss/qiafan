package com.example.qiafan.fanfou

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TimelinePaginationTest {
    private val author = FanfouUser("author", "作者", "", false)
    private fun status(id: Int) = FanfouStatus("post-$id", "动态 $id", "", author, false, null, null, null)

    @Test fun loadingMoreKeepsExistingCardsAndScrollPositionAndAppendsUniquePosts() = timeline { activity, body ->
        val cards = (0 until 20).map(body::getChildAt)
        val scroll = field(activity, "currentScroll") as ScrollView
        layout(scroll)
        scroll.scrollTo(0, 200)
        val previousY = scroll.scrollY
        assertTrue(previousY > 0)
        var requestedCursor: String? = null
        set(activity, "statusLoader", { cursor: String? ->
            requestedCursor = cursor
            (20..39).map(::status)
        })

        loadMore(activity)
        layout(scroll)

        assertEquals("post-20", requestedCursor)
        cards.forEachIndexed { index, card ->
            assertSame("加载更多不应重新创建已有卡片", card, body.getChildAt(index))
        }
        assertEquals(previousY, scroll.scrollY)
        assertEquals((1..39).map { "动态 $it" }, texts(body).filter { it.startsWith("动态 ") })
        assertEquals("post-39", field(activity, "nextId"))
        assertEquals(39, (field(activity, "homeItems") as List<*>).size)
    }

    @Test fun failedPageCanRetryWithoutReplacingCardsOrLeavingErrorRows() = timeline { activity, body ->
        val firstCard = body.getChildAt(0)
        set(activity, "statusLoader", { _: String? -> error("分页网络失败") })
        loadMore(activity)
        assertSame(firstCard, body.getChildAt(0))
        assertEquals("post-20", field(activity, "nextId"))
        assertTrue(texts(body).any { it.contains("分页网络失败") })
        loadMore(activity)
        assertEquals("重复失败应替换底部提示", 1, texts(body).count { it.contains("分页网络失败") })

        set(activity, "statusLoader", { _: String? -> listOf(status(20), status(21)) })
        val retry = descendants(body).first { it is TextView && it.text.toString() == "重试" }
        retry.performClick()
        finishRequest(activity)

        assertSame("重试成功也应保留旧卡片", firstCard, body.getChildAt(0))
        assertFalse(texts(body).any { it.contains("分页网络失败") })
        assertEquals((1..21).map { "动态 $it" }, texts(body).filter { it.startsWith("动态 ") })
        assertNull(field(activity, "nextId"))
    }

    @Test fun duplicateOnlyPageEndsPaginationWithoutRebuildingCards() = timeline { activity, body ->
        val firstCard = body.getChildAt(0)
        set(activity, "statusLoader", { _: String? -> (1..20).map(::status) })
        loadMore(activity)
        assertSame(firstCard, body.getChildAt(0))
        assertEquals(20, texts(body).count { it.startsWith("动态 ") })
        assertFalse(texts(body).contains("继续下滑，加载更多"))
        assertNull(field(activity, "nextId"))
    }

    @Test fun cachedHomeKeepsNumericOrderWithoutReplacingExistingCards() = timeline { activity, body ->
        set(activity, "items", mutableListOf(status(100).copy(id = "100"), status(90).copy(id = "90")))
        render(activity)
        val first = body.getChildAt(0)
        val second = body.getChildAt(1)
        set(activity, "statusLoader", { _: String? ->
            listOf(status(80).copy(id = "80"), status(95).copy(id = "95"))
        })
        loadMore(activity)
        assertSame(first, body.getChildAt(0))
        assertSame(second, body.getChildAt(2))
        assertEquals(listOf("动态 100", "动态 95", "动态 90", "动态 80"), texts(body).filter { it.startsWith("动态 ") })
    }

    @Test fun loadingQuotedOriginalUpdatesOnlyItsOwnCard() = timeline { activity, body ->
        @Suppress("UNCHECKED_CAST")
        val failed = field(activity, "quoteFailed") as MutableSet<String>
        failed.add("original")
        @Suppress("UNCHECKED_CAST")
        val items = field(activity, "items") as MutableList<FanfouStatus>
        items[1] = items[1].copy(text = "@作者 收到", replyToStatusId = "original", replyToScreenName = "作者")
        render(activity)
        val first = body.getChildAt(0)
        val last = body.getChildAt(19)
        @Suppress("UNCHECKED_CAST")
        val quotes = field(activity, "quoteContext") as MutableMap<String, FanfouStatus>
        quotes["original"] = status(99).copy(id = "original", text = "引用的原文")
        FanfouActivity::class.java.getDeclaredMethod("scheduleQuoteRender", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "original")
        shadowOf(Looper.getMainLooper()).idle()
        assertSame("补拉引用不应重建整页", first, body.getChildAt(0))
        assertSame(last, body.getChildAt(19))
        assertTrue(texts(body.getChildAt(1)).contains("引用的原文"))
    }

    private fun render(activity: FanfouActivity) {
        FanfouActivity::class.java.getDeclaredMethod("renderStatuses", Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.invoke(activity, true, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun timeline(check: (FanfouActivity, LinearLayout) -> Unit) {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val activity = controller.get()
            (field(activity, "io") as ExecutorService).shutdownNow()
            set(activity, "io", Executors.newSingleThreadExecutor())
            set(activity, "items", (1..20).map(::status).toMutableList())
            set(activity, "nextId", "post-20")
            set(activity, "statusLoader", { _: String? -> emptyList<FanfouStatus>() })
            FanfouActivity::class.java.getDeclaredMethod("shell", String::class.java, Boolean::class.javaPrimitiveType, View::class.java, View::class.java)
                .apply { isAccessible = true }.invoke(activity, "关注", true, null, null)
            render(activity)
            check(activity, field(activity, "body") as LinearLayout)
        } finally { controller.pause().stop().destroy() }
    }

    private fun loadMore(activity: FanfouActivity) {
        FanfouActivity::class.java.getDeclaredMethod("loadStatuses", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false, false)
        finishRequest(activity)
    }

    private fun finishRequest(activity: FanfouActivity) {
        (field(activity, "io") as ExecutorService).submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun field(activity: FanfouActivity, name: String): Any? =
        FanfouActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(activity: FanfouActivity, name: String, value: Any) =
        FanfouActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun texts(view: View): List<String> = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
}
