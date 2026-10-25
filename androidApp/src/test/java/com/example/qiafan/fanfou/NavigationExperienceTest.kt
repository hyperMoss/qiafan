package com.example.qiafan.fanfou

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationExperienceTest {
    @Test fun firstVisitToNotificationsImmediatelyShowsItsCategories() {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val activity = controller.get()
            FanfouActivity::class.java.getDeclaredField("activeTab").apply { isAccessible = true }.set(activity, "通知")
            FanfouActivity::class.java.getDeclaredMethod("showNotifications").apply { isAccessible = true }.invoke(activity)
            val texts = texts(activity.window.decorView)
            assertTrue("首次打开应立即显示通知分类，不等待网络", texts.any { it.startsWith("@我的") })
            assertTrue(texts.any { it.startsWith("私信") })
            assertTrue(texts.any { it.startsWith("关注请求") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun cachedNotificationsAreVisibleImmediatelyAndKeepCategoryNavigation() {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val a = controller.get()
            set(a, "activeTab", "通知")
            val author = FanfouUser("someone", "示例用户", "", false)
            set(a, "notificationSnapshot", NotificationSnapshot(
                mentions = Result.success(listOf(FanfouStatus("cached", "缓存中的提及，离线也能读。", "", author, false, null, null, null))),
                conversations = Result.success(emptyList()), requests = Result.success(emptyList())
            ))
            invoke(a, "showNotifications")
            val texts = texts(a.window.decorView)
            assertTrue(texts.contains("缓存中的提及，离线也能读。"))
            assertFalse(texts.contains("正在同步通知…"))
            snapshot(a.window.decorView, "notifications")
            descendants(a.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "私信" }.performClick()
            assertTrue(texts(a.window.decorView).contains("暂无私信"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun profilePlacesChatBesideFollowAndNavigationOnNextRow() = profile(false)
    @Test fun lockedProfileDisablesChatEvenWhenAlreadyFollowed() = profile(true)

    private fun profile(locked: Boolean) {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val a = controller.get()
            val user = FanfouUser("reader", "示例用户", "", locked, "认真记录生活。", following = locked)
            set(a, "loadedProfileId", user.id)
            val header = FanfouActivity::class.java.getDeclaredMethod("profileHeader", FanfouUser::class.java)
                .apply { isAccessible = true }.invoke(a, user) as ViewGroup
            val chat = descendants(header).filterIsInstance<TextView>().first { it.text.toString() == "私聊" }
            assertEquals(!locked, chat.isEnabled)
            val controls = chat.parent as ViewGroup
            assertEquals(2, controls.childCount)
            assertTrue(texts(controls).contains(if (locked) "已关注" else "关注"))
            val links = descendants(header).first { it.contentDescription == "TA看到的，打开用户主页时间线" }
            assertNotSame(controls, links.parent)
            assertEquals(listOf("关注", "被关注", "TA看到的"), texts(links.parent as View))
            snapshot(header, if (locked) "profile-locked" else "profile-open")
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun exploreReplacesPopularTopicsAndRestoresItsLoadedPosts() {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val a = controller.get()
            set(a, "activeTab", "随便看看")
            val user = FanfouUser("someone", "示例用户", "", false)
            set(a, "exploreItems", listOf(FanfouStatus("public", "上次看到的公开动态", "", user, false, null, null, null)))
            invoke(a, "showExplore")
            val texts = texts(a.window.decorView)
            assertTrue(texts.contains("上次看到的公开动态"))
            assertFalse(texts.contains("热门话题"))
            assertFalse(texts.contains("热门"))
            assertTrue(texts.contains("随便看看"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun searchNoLongerContainsTheDirectTimelineForm() {
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try {
            val a = controller.get()
            invoke(a, "showSearch")
            assertFalse(texts(a.window.decorView).contains("直接打开用户时间线"))
        } finally { controller.pause().stop().destroy() }
    }

    private fun set(a: FanfouActivity, field: String, value: Any) =
        FanfouActivity::class.java.getDeclaredField(field).apply { isAccessible = true }.set(a, value)
    private fun invoke(a: FanfouActivity, method: String) =
        FanfouActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(a)
    private fun descendants(v: View): List<View> = listOf(v) +
        (if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList())
    private fun snapshot(v: View, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, 1080, v.measuredHeight)
        val image = Bitmap.createBitmap(1080, v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(image))
        val file = File("build/reports/navigation-experience/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun texts(view: View): List<String> =
        (if (view is TextView) listOf(view.text.toString()) else emptyList()) +
            (if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
}
