package com.example.qiafan.fanfou

import android.text.Html
import android.util.Log
import com.example.qiafan.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class FanfouCredentials(
    val consumerKey: String,
    val consumerSecret: String,
    val token: String = "",
    val tokenSecret: String = ""
)

internal data class OAuthToken(val value: String, val secret: String)

/** Public application credentials published by fanfoujs/nofan in source/util.ts. */
internal object NofanApplication {
    const val consumerKey = "13456aa784cdf7688af69e85d482e011"
    const val consumerSecret = "f75c02df373232732b69354ecfbcabea"

    fun credentials(token: OAuthToken = OAuthToken("", "")) = FanfouCredentials(
        consumerKey, consumerSecret, token.value, token.secret
    )
}

internal data class FanfouUser(
    val id: String,
    val name: String,
    val avatar: String,
    val protected: Boolean,
    val description: String = "",
    val largeAvatar: String = avatar
)

internal data class FanfouStatus(
    val id: String,
    val text: String,
    val createdAt: String,
    val user: FanfouUser,
    val favorited: Boolean,
    val photo: String?,
    val originalPhoto: String?,
    val repostId: String?,
    val replyToStatusId: String = "",
    val replyToUserId: String = "",
    val replyToScreenName: String = ""
)

internal data class FanfouTrend(val name: String, val query: String)

internal data class FanfouCounts(val mentions: Int, val messages: Int, val requests: Int)

internal data class FanfouMessage(
    val id: String,
    val sender: FanfouUser,
    val text: String,
    val createdAt: String
)

/** 会话列表里的一行；[unread] 来自服务端 new_conv，只表示有无未读，不代表条数。 */
internal data class FanfouConversation(
    val peer: FanfouUser,
    val lastText: String,
    val lastAt: String,
    val lastFromMe: Boolean,
    val unread: Boolean,
    val messageCount: Int
)

internal class FanfouApiException(val code: Int, message: String) : Exception(message)

/**
 * OAuth 1.0a client for the documented Fanfou API. xAuth exchanges a password for a token once;
 * the password is not stored. Requests use HTTPS, while the OAuth signature uses the HTTP URL
 * as in nofan's compatibility hook. Redirects are rejected.
 */
internal class FanfouApi(private val credentials: () -> FanfouCredentials) {
    private val apiBase = "https://api.fanfou.com"
    private val authBase = "https://fanfou.com"

    fun xauth(username: String, password: String): OAuthToken {
        require(username.isNotBlank() && password.isNotBlank())
        val body = request(
            "POST", authBase + "/oauth/access_token",
            mapOf(
                "x_auth_mode" to "client_auth",
                "x_auth_username" to username.trim(),
                "x_auth_password" to password
            ),
            NofanApplication.credentials(),
            includeFormInSignature = false
        )
        return tokenFrom(body)
    }

    fun verify(): FanfouUser =
        userFrom(JSONObject(apiGet("/account/verify_credentials.json")))

    fun showUser(userId: String): FanfouUser =
        userFrom(JSONObject(apiGet("/users/show.json", mapOf("id" to userId))))

    fun home(maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/statuses/home_timeline.json", page(maxId)))

    fun userTimeline(userId: String, maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/statuses/user_timeline.json", page(maxId) + ("id" to userId)))

    fun publicTimeline(maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/statuses/public_timeline.json", page(maxId)))

    fun userPhotos(userId: String, pageNumber: Int = 1): List<FanfouStatus> =
        statuses(apiGet("/photos/user_timeline.json", mapOf(
            "id" to userId, "count" to "20", "page" to pageNumber.toString()
        )))

    fun mentions(maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/statuses/mentions.json", page(maxId)))

    fun favorites(pageNumber: Int = 1): List<FanfouStatus> =
        statuses(apiGet("/favorites.json", mapOf("count" to "20", "page" to pageNumber.toString())))

    fun userFavorites(userId: String, pageNumber: Int = 1): List<FanfouStatus> =
        statuses(apiGet("/favorites/" + encode(userId) + ".json", mapOf(
            "count" to "20", "page" to pageNumber.toString()
        )))

    fun publicSearch(query: String, maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/search/public_timeline.json", page(maxId) + ("q" to query)))

    fun userSearch(userId: String, query: String, maxId: String? = null): List<FanfouStatus> =
        statuses(apiGet("/search/user_timeline.json", page(maxId) + mapOf("id" to userId, "q" to query)))

    fun searchUsers(query: String): List<FanfouUser> =
        users(apiGet("/search/users.json", mapOf("q" to query, "count" to "20")))

    fun showStatus(id: String): FanfouStatus =
        statusFrom(JSONObject(apiGet("/statuses/show.json", mapOf("id" to id))))

    fun counts(): FanfouCounts {
        val obj = JSONObject(apiGet("/account/notification.json"))
        return FanfouCounts(
            obj.optInt("mentions"),
            obj.optInt("direct_messages"),
            obj.optInt("friend_requests")
        )
    }

    /**
     * 通知页「私信」标签按会话成列。路径与字段以文档声明为准（new_conv 只表示有无未读），
     * 线上可用性与返回字段仍需授权账号实测。
     */
    fun conversations(page: Int = 1): List<FanfouConversation> {
        val array = JSONArray(apiGet(
            "/direct_messages/conversation_list.json",
            mapOf("count" to "20", "page" to page.toString())
        ))
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val dm = entry.optJSONObject("dm") ?: return@mapNotNull null
            val otherId = entry.optString("otherid").ifBlank { return@mapNotNull null }
            // 文档只在 dm.sender / dm.recipient 里给完整用户对象，另一端用裸 id 兜底。
            val peer = when (otherId) {
                dm.optString("sender_id") -> dm.optJSONObject("sender")
                dm.optString("recipient_id") -> dm.optJSONObject("recipient")
                else -> null
            }?.let { userFrom(it) } ?: FanfouUser(
                otherId,
                dm.optString("sender_id").takeIf { it == otherId }?.let {
                    dm.optString("sender_screen_name")
                } ?: dm.optString("recipient_screen_name"),
                "",
                false
            )
            FanfouConversation(
                peer = peer,
                lastText = plain(dm.optString("text")),
                lastAt = dm.optString("created_at"),
                lastFromMe = dm.optString("sender_id") != otherId,
                unread = entry.optBoolean("new_conv"),
                messageCount = entry.optInt("msg_num")
            )
        }
    }

    /** 某个会话的完整私信，按时间倒序返回；[maxId] 用于向前翻页。 */
    fun conversation(userId: String, maxId: String? = null): List<FanfouMessage> {
        val params = linkedMapOf("id" to userId, "count" to "30")
        maxId?.takeIf { it.isNotBlank() }?.let { params["max_id"] = it }
        val array = JSONArray(apiGet("/direct_messages/conversation.json", params))
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::messageFrom)
        }
    }

    fun followRequests(): List<FanfouUser> =
        users(apiGet("/friendships/requests.json"))

    /** 接受或拒绝一条关注请求；路径与参数以 Fanfou 文档声明为准，需用授权账号实测。 */
    fun respondToFollowRequest(userId: String, accept: Boolean) {
        require(userId.isNotBlank()) { "关注请求缺少用户 ID" }
        val path = if (accept) "/friendships/accept.json" else "/friendships/deny.json"
        apiPost(path, mapOf("id" to userId))
    }

    fun trends(): List<FanfouTrend> {
        val array = JSONObject(apiGet("/trends/list.json")).optJSONArray("trends") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            FanfouTrend(obj.optString("name"), obj.optString("query"))
        }
    }

    fun setFavorite(id: String, favorite: Boolean): FanfouStatus {
        val path = if (favorite) "/favorites/create/" else "/favorites/destroy/"
        return statusFrom(JSONObject(apiPost(path + encode(id) + ".json", emptyMap())))
    }

    fun repostText(status: FanfouStatus): String =
        "转@${status.user.name.ifBlank { status.user.id }} ${status.text}"

    fun repost(status: FanfouStatus, text: String = repostText(status)): FanfouStatus {
        require(status.id.isNotBlank()) { "缺少被转发的动态 ID" }
        require(text.isNotBlank() && text.length <= 140) { "转发正文须为 1 到 140 字，请编辑后发送" }
        return statusFrom(JSONObject(apiPost(
            "/statuses/update.json",
            mapOf("status" to text, "repost_status_id" to status.id)
        )))
    }

    /** 删除当前授权账号自己发送的消息。服务端权限是最终判定依据。 */
    fun deleteStatus(id: String) {
        require(id.isNotBlank()) { "缺少要删除的消息 ID" }
        apiPost("/statuses/destroy.json", mapOf("id" to id))
    }

    fun publish(text: String): FanfouStatus {
        require(text.isNotBlank() && text.length <= 140) { "动态正文须为 1 到 140 字" }
        return statusFrom(JSONObject(apiPost("/statuses/update.json", mapOf("status" to text))))
    }

    /**
     * 回复一条动态。走文档声明的 `in_reply_to_status_id` / `in_reply_to_user_id`，
     * 与普通发布同属 `POST /statuses/update`。
     *
     * 正文使用 `@对方昵称 ` 前缀，回复关系参数仍传用户 ID 和动态 ID。
     * 转发则在正文中拼 `转@昵称 原文` 并使用转发参数。
     * 前缀计入 140 字上限，所以正文按剩余额度截断。
     */
    fun reply(text: String, target: FanfouStatus): FanfouStatus {
        require(text.isNotBlank()) { "回复内容不能为空" }
        require(target.id.isNotBlank()) { "缺少被回复的动态 ID" }
        require(target.user.id.isNotBlank()) { "缺少被回复用户 ID" }
        val prefix = "@${replyName(target)} "
        val room = 140 - prefix.length
        require(room > 0) { "被回复用户昵称过长" }
        val body = text.take(room)
        val params = linkedMapOf("status" to prefix + body, "in_reply_to_status_id" to target.id)
        params["in_reply_to_user_id"] = target.user.id
        return statusFrom(JSONObject(apiPost("/statuses/update.json", params)))
    }

    fun replyName(target: FanfouStatus): String = target.user.name.ifBlank { target.user.id }

    /** 写评论时会被自动加上的 `@对方昵称 ` 前缀长度，界面用它算剩余字数。 */
    fun replyPrefixLength(target: FanfouStatus): Int =
        replyName(target).takeIf { it.isNotBlank() }?.let { "@$it ".length } ?: 140

    /**
     * 读评论的现实做法：官方没有 comments 端点，只能用 `context_timeline` 取这条动态的
     * 上下文，再按 `in_reply_to_status_id` 过滤出真正指向它的回复。
     * 返回的是服务端真实数据，过滤不到就是没有回复，不做任何补齐。
     */
    fun replies(statusId: String): List<FanfouStatus> =
        statuses(apiGet("/statuses/context_timeline.json", mapOf("id" to statusId)))
            .filter { it.replyToStatusId == statusId }

    fun sendDirectMessage(userId: String, text: String) {
        require(userId.isNotBlank() && text.isNotBlank() && text.length < 140) {
            "私信须指定用户，并填写少于 140 字"
        }
        apiPost("/direct_messages/new.json", mapOf("user" to userId, "text" to text))
    }

    private fun page(maxId: String?): Map<String, String> =
        if (maxId.isNullOrBlank()) mapOf("count" to "20")
        else mapOf("count" to "20", "max_id" to maxId)

    private fun apiGet(path: String, params: Map<String, String> = emptyMap()): String =
        request("GET", apiBase + path, params, credentials())

    private fun apiPost(path: String, params: Map<String, String>): String =
        request("POST", apiBase + path, params, credentials())

    private fun request(
        method: String,
        endpoint: String,
        params: Map<String, String>,
        account: FanfouCredentials,
        extraOAuth: Map<String, String> = emptyMap(),
        includeFormInSignature: Boolean = true
    ): String {
        require(endpoint.startsWith("https://"))
        require(account.consumerKey.isNotBlank() && account.consumerSecret.isNotBlank())
        val oauth = linkedMapOf(
            "oauth_consumer_key" to account.consumerKey,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0"
        )
        if (account.token.isNotBlank()) oauth["oauth_token"] = account.token
        oauth.putAll(extraOAuth)
        val signatureParams = (oauth.toList() + if (includeFormInSignature) params.toList() else emptyList())
            .map { encode(it.first) + "=" + encode(it.second) }
            .sorted()
            .joinToString("&")
        val signingEndpoint = "http://" + endpoint.removePrefix("https://")
        val base = method + "&" + encode(signingEndpoint) + "&" + encode(signatureParams)
        val key = encode(account.consumerSecret) + "&" + encode(account.tokenSecret)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        oauth["oauth_signature"] = android.util.Base64.encodeToString(
            mac.doFinal(base.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP
        )
        val form = params.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val target = if (method == "GET" && form.isNotEmpty()) endpoint + "?" + form else endpoint
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(
            "Authorization",
            "OAuth " + oauth.entries.joinToString(", ") {
                encode(it.key) + "=\"" + encode(it.value) + "\""
            }
        )
        if (method == "POST") {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val error = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
                throw FanfouApiException(code, if (error.isBlank()) "饭否请求失败（" + code + "）" else error)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun tokenFrom(body: String): OAuthToken {
        val fields = body.split("&").mapNotNull {
            val pair = it.split("=", limit = 2)
            if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to
                URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        return OAuthToken(
            fields["oauth_token"] ?: error("授权响应没有 token"),
            fields["oauth_token_secret"] ?: error("授权响应没有 token secret")
        )
    }

    private fun statuses(body: String): List<FanfouStatus> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::statusFrom) }
    }

    private fun users(body: String): List<FanfouUser> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::userFrom) }
    }

    private fun messageFrom(obj: JSONObject): FanfouMessage {
        val sender = obj.optJSONObject("sender") ?: obj.optJSONObject("user") ?: JSONObject()
        return FanfouMessage(
            obj.optString("id"),
            userFrom(sender),
            plain(obj.optString("text")),
            obj.optString("created_at")
        )
    }

    private fun statusFrom(obj: JSONObject): FanfouStatus {
        val photo = obj.optJSONObject("photo")
        return FanfouStatus(
            obj.optString("id"),
            plain(obj.optString("text")),
            obj.optString("created_at"),
            userFrom(obj.optJSONObject("user") ?: JSONObject()),
            obj.optBoolean("favorited"),
            sequenceOf("imageurl", "thumburl", "largeurl")
                .mapNotNull { key -> checkedMediaUrl(photo?.optString(key)) }
                .firstOrNull(),
            checkedMediaUrl(photo?.optString("largeurl")),
            obj.optString("repost_status_id").takeIf { it.isNotBlank() },
            obj.optString("in_reply_to_status_id"),
            obj.optString("in_reply_to_user_id"),
            obj.optString("in_reply_to_screen_name")
        )
    }

    private fun userFrom(obj: JSONObject): FanfouUser = FanfouUser(
        obj.optString("id"),
        obj.optString("screen_name").ifBlank { obj.optString("name") },
        checkedMediaUrl(obj.optString("profile_image_url"))
            ?: checkedMediaUrl(obj.optString("profile_image_url_large")) ?: "",
        obj.optBoolean("protected"),
        plain(obj.optString("description")),
        checkedMediaUrl(obj.optString("profile_image_url_large"))
            ?: checkedMediaUrl(obj.optString("profile_image_url")) ?: ""
    )

    companion object {
        fun plain(value: String): String =
            Html.fromHtml(value).toString().trim()

        private fun checkedMediaUrl(value: String?): String? {
            val secured = secureMediaUrl(value)
            if (secured == null && !value.isNullOrBlank() && BuildConfig.DEBUG) {
                val host = runCatching { URL(if (value.startsWith("//")) "https:$value" else value).host }
                    .getOrDefault("invalid")
                Log.w("QiafanMedia", "Rejected media host: $host")
            }
            return secured
        }

        fun secureMediaUrl(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val input = if (value.startsWith("//")) "https:$value" else value
            val url = runCatching { URL(input) }.getOrNull() ?: return null
            if (url.protocol !in listOf("http", "https") || '@' in url.authority ||
                url.port != -1 && url.port != 443 && url.port != 80
            ) return null
            val host = url.host.lowercase()
            if (host != "fanfou.com" && !host.endsWith(".fanfou.com") &&
                host != "lcff.com" && !host.endsWith(".lcff.com") &&
                host != "meituan.net" && !host.endsWith(".meituan.net")
            ) return null
            return "https://" + url.host + url.file
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~")
    }
}
