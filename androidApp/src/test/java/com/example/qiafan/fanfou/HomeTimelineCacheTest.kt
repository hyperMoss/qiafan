package com.example.qiafan.fanfou

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class HomeTimelineCacheTest {
    private val owner = NofanApplication.credentials(OAuthToken("test-token-value", "test-secret-value"))
    private val otherOwner = NofanApplication.credentials(OAuthToken("another-token", "another-secret"))

    /** 与 SecureSession 一样按（路径, 账号）存取，因此换号读不回来。 */
    private class MemoryCache : NotificationResponseCache {
        private val entries = mutableMapOf<Pair<String, FanfouCredentials>, String>()
        override fun readNotification(path: String, owner: FanfouCredentials): String? = entries[path to owner]
        override fun writeNotification(path: String, payload: String, owner: FanfouCredentials) {
            entries[path to owner] = payload
        }
        fun payload(path: String, owner: FanfouCredentials): String? = entries[path to owner]
    }

    @Test fun loadedTimelineAndReadingAnchorSurviveRoundTrip() {
        val snapshot = HomeTimelineSnapshot(
            statuses = listOf(
                FanfouStatus(
                    id = "9",
                    text = "看这个 @饭友 和 https://fanfou.com/x",
                    createdAt = "Wed Jul 17 09:00:00 +0800 2024",
                    user = FanfouUser(
                        id = "7",
                        name = "甲",
                        avatar = "https://fanfou.com/a.png",
                        protected = true,
                        description = "签名",
                        largeAvatar = "https://fanfou.com/a-large.png",
                        following = null
                    ),
                    favorited = true,
                    photo = "https://fanfou.com/p.png",
                    originalPhoto = "https://fanfou.com/p-large.png",
                    repostId = "8",
                    replyToStatusId = "6",
                    replyToUserId = "5",
                    replyToScreenName = "乙",
                    links = listOf(FanfouTextLink(start = 10, end = 30, url = "https://fanfou.com/x")),
                    emphasis = listOf(FanfouTextEmphasis(start = 0, end = 3)),
                    mentions = listOf(FanfouMention(start = 4, end = 7, userId = "5"))
                ),
                FanfouStatus(
                    id = "no-numeric-id",
                    text = "纯文字",
                    createdAt = "Thu Jul 18 10:00:00 +0800 2024",
                    user = FanfouUser(
                        id = "author-with-long-id",
                        name = "丙",
                        avatar = "",
                        protected = false,
                        following = false
                    ),
                    favorited = false,
                    photo = null,
                    originalPhoto = null,
                    repostId = null
                )
            ),
            nextId = null,
            anchorId = "no-numeric-id",
            anchorOffset = 128
        )
        val cache = HomeTimelineCache(MemoryCache())

        cache.write(snapshot, owner)

        assertEquals(snapshot, cache.read(owner))
    }

    @Test fun pagingCursorIsKeptApartFromMissingOne() {
        val cache = HomeTimelineCache(MemoryCache())
        val snapshot = HomeTimelineSnapshot(
            statuses = listOf(microPost(id = "older-cursor-42")),
            nextId = "older-cursor-42",
            anchorId = "older-cursor-42",
            anchorOffset = 0
        )

        cache.write(snapshot, owner)

        assertEquals("older-cursor-42", cache.read(owner)?.nextId)
    }

    @Test fun unreadableCacheReadsAsNoCache() {
        val payloads = listOf(
            "not json at all",
            """{"statuses":[],"anchorId":"x"}""",
            """{"statuses":[{"id":"1"}],"nextId":null,"anchorId":"1","anchorOffset":0}"""
        )
        payloads.forEach { payload ->
            val cache = MemoryCache()
            cache.writeNotification(HomeTimelineCache.snapshotKey, payload, owner)

            assertNull("损坏内容不应还原成半截时间线：$payload", HomeTimelineCache(cache).read(owner))
        }
    }

    @Test fun snapshotIsWrittenOwnerBoundAndWithoutCredentials() {
        val cache = MemoryCache()
        val reader = HomeTimelineCache(cache)
        val snapshot = HomeTimelineSnapshot(listOf(microPost(id = "9")), "9", "9", 24)

        reader.write(snapshot, owner)

        val payload = cache.payload(HomeTimelineCache.snapshotKey, owner) ?: error("快照应写入固定存储键")
        assertFalse(payload.contains(owner.token))
        assertFalse(payload.contains(owner.tokenSecret))
        assertNull(reader.read(otherOwner))
        assertEquals(snapshot, reader.read(owner))
    }

    private fun microPost(id: String) = FanfouStatus(
        id = id,
        text = "内容",
        createdAt = "now",
        user = FanfouUser(id = "u", name = "作者", avatar = "", protected = false),
        favorited = false,
        photo = null,
        originalPhoto = null,
        repostId = null
    )
}
