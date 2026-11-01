package com.example.qiafan.fanfou

import org.json.JSONArray
import org.json.JSONObject

internal data class HomeTimelineSnapshot(
    val statuses: List<FanfouStatus>,
    val nextId: String?,
    val anchorId: String,
    val anchorOffset: Int
)

/**
 * 关注流阅读现场（已加载动态、分页位置与滚动锚点）的跨进程保存。
 *
 * 只序列化 [FanfouStatus] 的领域字段，不写任何账号凭据；加密、账号边界、24 小时过期和
 * 退出登录清理都由 [NotificationResponseCache] 一侧提供。缓存内容解析失败按没有缓存处理。
 */
internal class HomeTimelineCache(private val cache: NotificationResponseCache) {

    fun read(owner: FanfouCredentials): HomeTimelineSnapshot? {
        val payload = cache.readNotification(snapshotKey, owner) ?: return null
        return runCatching { snapshotFrom(JSONObject(payload)) }.getOrNull()
    }

    fun write(snapshot: HomeTimelineSnapshot, owner: FanfouCredentials) {
        cache.writeNotification(snapshotKey, snapshotToJson(snapshot).toString(), owner)
    }

    internal companion object {
        const val snapshotKey = "home:/reading-snapshot-v1"
    }
}

private fun snapshotToJson(snapshot: HomeTimelineSnapshot): JSONObject = JSONObject()
    .put("nextId", snapshot.nextId ?: JSONObject.NULL)
    .put("anchorId", snapshot.anchorId)
    .put("anchorOffset", snapshot.anchorOffset)
    .put("statuses", JSONArray().apply { snapshot.statuses.forEach { put(statusToJson(it)) } })

private fun snapshotFrom(obj: JSONObject): HomeTimelineSnapshot {
    val statuses = obj.getJSONArray("statuses")
    return HomeTimelineSnapshot(
        statuses = List(statuses.length()) { statusFrom(statuses.getJSONObject(it)) },
        nextId = obj.nullableString("nextId"),
        anchorId = obj.getString("anchorId"),
        anchorOffset = obj.getInt("anchorOffset")
    )
}

private fun statusToJson(status: FanfouStatus): JSONObject = JSONObject()
    .put("id", status.id)
    .put("text", status.text)
    .put("createdAt", status.createdAt)
    .put("user", userToJson(status.user))
    .put("favorited", status.favorited)
    .put("photo", status.photo ?: JSONObject.NULL)
    .put("originalPhoto", status.originalPhoto ?: JSONObject.NULL)
    .put("repostId", status.repostId ?: JSONObject.NULL)
    .put("replyToStatusId", status.replyToStatusId)
    .put("replyToUserId", status.replyToUserId)
    .put("replyToScreenName", status.replyToScreenName)
    .put("links", JSONArray().apply {
        status.links.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("url", it.url)) }
    })
    .put("emphasis", JSONArray().apply {
        status.emphasis.forEach { put(JSONObject().put("start", it.start).put("end", it.end)) }
    })
    .put("mentions", JSONArray().apply {
        status.mentions.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("userId", it.userId)) }
    })

private fun statusFrom(obj: JSONObject): FanfouStatus = FanfouStatus(
    id = obj.getString("id"),
    text = obj.getString("text"),
    createdAt = obj.getString("createdAt"),
    user = userFrom(obj.getJSONObject("user")),
    favorited = obj.getBoolean("favorited"),
    photo = obj.nullableString("photo"),
    originalPhoto = obj.nullableString("originalPhoto"),
    repostId = obj.nullableString("repostId"),
    replyToStatusId = obj.getString("replyToStatusId"),
    replyToUserId = obj.getString("replyToUserId"),
    replyToScreenName = obj.getString("replyToScreenName"),
    links = obj.getJSONArray("links").mapObjects { FanfouTextLink(it.getInt("start"), it.getInt("end"), it.getString("url")) },
    emphasis = obj.getJSONArray("emphasis").mapObjects { FanfouTextEmphasis(it.getInt("start"), it.getInt("end")) },
    mentions = obj.getJSONArray("mentions").mapObjects { FanfouMention(it.getInt("start"), it.getInt("end"), it.getString("userId")) }
)

private fun userToJson(user: FanfouUser): JSONObject = JSONObject()
    .put("id", user.id)
    .put("name", user.name)
    .put("avatar", user.avatar)
    .put("protected", user.protected)
    .put("description", user.description)
    .put("largeAvatar", user.largeAvatar)
    .put("following", user.following ?: JSONObject.NULL)

private fun userFrom(obj: JSONObject): FanfouUser = FanfouUser(
    id = obj.getString("id"),
    name = obj.getString("name"),
    avatar = obj.getString("avatar"),
    protected = obj.getBoolean("protected"),
    description = obj.getString("description"),
    largeAvatar = obj.getString("largeAvatar"),
    following = obj.nullableBoolean("following")
)

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.nullableBoolean(key: String): Boolean? = if (isNull(key)) null else getBoolean(key)
