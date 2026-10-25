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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationTimeTest {
    private fun withActivity(block: (FanfouActivity) -> Unit) {
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val controller = Robolectric.buildActivity(FanfouActivity::class.java).setup()
        try { block(controller.get()) } finally {
            controller.pause().stop().destroy()
            TimeZone.setDefault(oldZone)
        }
    }

    private fun raw(date: Date) = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).format(date)
    private fun expected(date: Date): String {
        val then = Calendar.getInstance().apply { time = date }
        val now = Calendar.getInstance()
        return SimpleDateFormat(if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm", Locale.CHINA).format(date)
    }
    private fun texts(v: View): List<String> = (if (v is TextView) listOf(v.text.toString()) else emptyList()) +
        (if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else emptyList())

    @Test fun oldMentionNotificationsUseLocalDateInsteadOfTheRawServerTimestamp() = withActivity { a ->
        val date = Date(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        val stamp = raw(date)
        val status = FanfouStatus("old", "较早的提及", stamp, FanfouUser("peer", "朋友", "", false), false, null, null, null)
        FanfouActivity::class.java.getDeclaredField("activeTab").apply { isAccessible = true }.set(a, "通知")
        FanfouActivity::class.java.getDeclaredField("notificationSnapshot").apply { isAccessible = true }
            .set(a, NotificationSnapshot(mentions = Result.success(listOf(status))))
        FanfouActivity::class.java.getDeclaredMethod("showNotifications").apply { isAccessible = true }.invoke(a)
        val shown = texts(a.window.decorView)
        assertFalse("旧通知不应直接显示含英文与时区的服务端时间", shown.contains(stamp))
        assertTrue(shown.contains(expected(date)))
    }

    @Test fun privateMessageNotificationsFromEarlierYearsIncludeTheYear() = withActivity { a ->
        val date = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.time
        val conversation = FanfouConversation(FanfouUser("peer", "朋友", "", false), "旧私信", raw(date), false, false, 1)
        val row = FanfouActivity::class.java.getDeclaredMethod("dmRow", FanfouConversation::class.java)
            .apply { isAccessible = true }.invoke(a, conversation) as View
        assertTrue("跨年私信必须显示年份，不能看起来像今年", texts(row).contains(expected(date)))
    }

    @Test fun invalidCalendarDatesAreNotSilentlyMovedToAnotherDay() = withActivity { a ->
        val parsed = FanfouActivity::class.java.getDeclaredMethod("parseFanfouTime", String::class.java)
            .apply { isAccessible = true }.invoke(a, "Mon Feb 30 12:00:00 +0800 2026")
        assertNull("无效日期不能滚动成另一天", parsed)
    }
    @Test fun timezoneOffsetsReferToTheSameInstant() = withActivity { a ->
        val method = FanfouActivity::class.java.getDeclaredMethod("parseFanfouTime", String::class.java).apply { isAccessible = true }
        val utc = method.invoke(a, "Sun Oct 25 00:30:00 +0000 2026")
        val local = method.invoke(a, "Sun Oct 25 08:30:00 +0800 2026")
        assertNotNull(utc)
        assertEquals(utc, local)
    }

    @Test fun recentNotificationsKeepTheirRelativeTime() = withActivity { a ->
        val date = Date(System.currentTimeMillis() - 5 * 60_000L)
        val shown = FanfouActivity::class.java.getDeclaredMethod("relativeTime", String::class.java)
            .apply { isAccessible = true }.invoke(a, raw(date))
        assertEquals("5 分钟前", shown)
    }

    @Test fun missingOrMalformedTimesNeverLeakRawTimestampText() = withActivity { a ->
        for (method in listOf("relativeTime", "chatClock")) {
            val format = FanfouActivity::class.java.getDeclaredMethod(method, String::class.java).apply { isAccessible = true }
            for (value in listOf("", "not-a-date", "Sun Oct 25 08:30:00 +0800 2026 trailing")) {
                assertEquals("时间未知", format.invoke(a, value))
            }
        }
    }

}
