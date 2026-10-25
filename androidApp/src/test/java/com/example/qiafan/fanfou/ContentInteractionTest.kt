package com.example.qiafan.fanfou

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ContentInteractionTest {
    private val user = FanfouUser("friend", "朋友", "", false)
    private val original = FanfouStatus("original", "这是被转发的原文", "", user, false, null, null, null)

    private fun withActivity(block: (FanfouActivity) -> Unit) {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }
    private fun descendants(v: View): List<View> = listOf(v) +
        (if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
    private fun cache(a: FanfouActivity) {
        FanfouActivity::class.java.getDeclaredField("quoteContext").apply { isAccessible = true }
            .set(a, mutableMapOf(original.id to original))
    }
    private fun target(a: FanfouActivity) = FanfouActivity::class.java.getDeclaredField("commentTarget")
        .apply { isAccessible = true }.get(a) as? FanfouStatus
    private fun tapText(container: View, text: String) {
        container.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        container.layout(0, 0, 720, container.measuredHeight)
        val body = descendants(container).filterIsInstance<TextView>().first { it.text.toString() == text }
        val now = SystemClock.uptimeMillis()
        val x = body.totalPaddingLeft + 3f
        val y = body.totalPaddingTop + body.layout.getLineBottom(0) / 2f
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 30)) {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            body.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test fun tappingLoadedOriginalTextOpensTheOriginalRatherThanTheRepost() = withActivity { a ->
        cache(a)
        val quote = FanfouActivity::class.java.getDeclaredMethod("originalStatusCard", FanfouStatus::class.java, List::class.java)
            .apply { isAccessible = true }.invoke(a, original, listOf(user)) as View
        tapText(quote, original.text)
        assertEquals("点击引用正文应进入原动态", original.id, target(a)?.id)
    }

    @Test fun tappingEmbeddedQuoteTextAlsoOpensTheCorrespondingOriginal() = withActivity { a ->
        val quote = FanfouActivity::class.java.getDeclaredMethod("embeddedQuoteBox", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(a, user.name, original.text, original.id) as View
        cache(a)
        tapText(quote, original.text)
        assertEquals("转发内嵌原文也必须响应点击", original.id, target(a)?.id)
    }

    @Test fun tappingThePreviewImageClosesTheOverlay() = withActivity { a ->
        val root = FanfouActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(a) as FrameLayout
        val initial = root.childCount
        val status = original.copy(photo = "file:///nonexistent-qiafan-test.png")
        FanfouActivity::class.java.getDeclaredMethod("showPhoto", FanfouStatus::class.java)
            .apply { isAccessible = true }.invoke(a, status)
        assertEquals(initial + 1, root.childCount)
        val overlay = root.getChildAt(root.childCount - 1)
        descendants(overlay).filterIsInstance<ImageView>().single().performClick()
        assertEquals("再次点击图片应退出预览", initial, root.childCount)
    }
}
