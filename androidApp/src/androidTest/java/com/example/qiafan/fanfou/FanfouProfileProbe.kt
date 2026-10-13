package com.example.qiafan.fanfou

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.net.Uri
import android.text.Html
import android.text.style.URLSpan
import org.json.JSONArray
import org.json.JSONObject

internal object FanfouProfileProbe {
    fun read(context: Context): Pair<Int, Bundle> {
        val report = JSONObject()
        val result = runCatching {
            val session = SecureSession(context)
            val credentials = session.credentials() ?: error("No authorized device session")
            val api = FanfouApi { credentials }
            val get = FanfouApi::class.java.getDeclaredMethod("apiGet", String::class.java, Map::class.java)
                .apply { isAccessible = true }
            fun read(path: String, params: Map<String, String>): String =
                get.invoke(api, path, params) as String
            val home = JSONArray(read("/statuses/home_timeline.json", mapOf("count" to "20", "format" to "html")))
            val links = JSONArray()
            for (index in 0 until home.length()) {
                val status = home.getJSONObject(index)
                val decoded = Html.fromHtml(status.optString("text"))
                for (span in decoded.getSpans(0, decoded.length, URLSpan::class.java)) {
                    val label = decoded.subSequence(decoded.getSpanStart(span), decoded.getSpanEnd(span)).toString()
                    if (label.contains("LeeMay", ignoreCase = true)) {
                        links.put(JSONObject().put("label", label).put("url", span.url))
                    }
                }
            }
            report.put("homeMentionTargets", links)
            val matched = JSONArray()
            val ids = (0 until links.length()).map {
                Uri.parse(links.getJSONObject(it).getString("url")).lastPathSegment.orEmpty()
            }.filter { it.isNotBlank() }.distinct()
            for (id in ids) {
                val full = JSONObject(read("/users/show.json", mapOf("id" to id)))
                matched.put(JSONObject()
                    .put("id", full.optString("id"))
                    .put("name", full.optString("screen_name"))
                    .put("protected", full.optBoolean("protected"))
                    .put("following", full.opt("following"))
                    .put("avatarPresent", full.optString("profile_image_url").isNotBlank())
                    .put("descriptionLength", full.optString("description").length))
            }
            report.put("canonicalUsers", matched)
        }
        if (result.isFailure) report.put("failure", result.exceptionOrNull()?.javaClass?.simpleName)
        return (if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED) to
            Bundle().apply { putString("stream", report.toString()) }
    }
}
