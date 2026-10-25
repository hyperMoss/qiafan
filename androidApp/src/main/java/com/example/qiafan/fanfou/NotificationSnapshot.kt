package com.example.qiafan.fanfou

internal interface NotificationResponseCache {
    fun readNotification(path: String, owner: FanfouCredentials): String?
    fun writeNotification(path: String, payload: String, owner: FanfouCredentials)
}

internal data class NotificationSnapshot(
    val counts: FanfouCounts = FanfouCounts(0, 0, 0),
    val mentions: Result<List<FanfouStatus>>? = null,
    val conversations: Result<List<FanfouConversation>>? = null,
    val requests: Result<List<FanfouUser>>? = null
)
