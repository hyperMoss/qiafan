package com.example.qiafan.fanfou

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NotificationCacheTest {
    private val owner = NofanApplication.credentials(OAuthToken("test-token", "test-secret"))
    private fun read(payloads: Map<String, String>): NotificationSnapshot {
        val cache = object : NotificationResponseCache {
            override fun readNotification(path: String, owner: FanfouCredentials) = payloads[path]
            override fun writeNotification(path: String, payload: String, owner: FanfouCredentials) = error("缓存读取不应发请求或覆盖缓存")
        }
        return FanfouApi(cache) { owner }.cachedNotifications()
    }

    @Test fun cachedMentionsRemainAvailableWithoutCountsOrOtherSections() {
        val result = read(mapOf("/statuses/mentions.json" to """[{"id":"one","text":"提及内容","user":{"id":"author","screen_name":"作者"}}]"""))
        assertEquals("提及内容", result.mentions?.getOrThrow()?.single()?.text)
        assertEquals(FanfouCounts(0, 0, 0), result.counts)
        assertNull(result.requests)
        assertNull(result.conversations)
    }

    @Test fun corruptSectionDoesNotHideOtherCachedSections() {
        val result = read(mapOf("/account/notification.json" to "invalid", "/statuses/mentions.json" to "[]", "/friendships/requests.json" to "[]"))
        assertTrue(result.mentions?.getOrThrow()?.isEmpty() == true)
        assertTrue(result.requests?.getOrThrow()?.isEmpty() == true)
        assertNull(result.conversations)
    }

    @Test fun cachedConversationUsesPeerAndLastMessage() {
        val result = read(mapOf("/direct_messages/conversation_list.json" to """[{"otherid":"peer","new_conv":true,"msg_num":3,"dm":{"sender_id":"peer","sender":{"id":"peer","screen_name":"朋友"},"text":"你好","created_at":"now"}}]"""))
        val row = result.conversations?.getOrThrow()?.single() ?: error("缺少缓存会话")
        assertEquals("朋友", row.peer.name)
        assertEquals("你好", row.lastText)
        assertTrue(row.unread)
        assertFalse(row.lastFromMe)
    }

    @Test fun missingCacheIsPendingRatherThanAFakeEmptyInbox() {
        val result = read(emptyMap())
        assertNull(result.mentions)
        assertNull(result.requests)
        assertNull(result.conversations)
    }

    @Test fun logoutClearsAllPersistedNotificationContent() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("fanfou_session", Context.MODE_PRIVATE)
        prefs.edit().putString("notification:/statuses/mentions.json", "cached-data").commit()
        SecureSession(context).logout()
        assertFalse(prefs.contains("notification:/statuses/mentions.json"))
    }
}
