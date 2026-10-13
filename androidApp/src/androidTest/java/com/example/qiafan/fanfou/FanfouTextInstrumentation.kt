package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject

class FanfouTextInstrumentation : Instrumentation() {
    private var profileProbe = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        profileProbe = arguments?.getString("probe") == "profile"
        start()
    }

    override fun onStart() {
        if (profileProbe) {
            val (code, output) = FanfouProfileProbe.read(targetContext)
            finish(code, output)
            return
        }
        val result = runCatching {
            val api = FanfouApi { FanfouCredentials("", "") }
            val parser = FanfouApi::class.java.getDeclaredMethod(
                "statusFrom", JSONObject::class.java, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            fun parse(text: String, search: Boolean): FanfouStatus = parser.invoke(
                api, JSONObject().put("id", "test").put("text", text), search
            ) as FanfouStatus

            val escaped = parse(" 前 &lt;strong&gt;https&lt;/strong&gt;://example.com 后 ", true)
            check(escaped.text == "前 https://example.com 后")
            check(escaped.emphasis == listOf(FanfouTextEmphasis(2, 7)))

            val linked = parse("<a href=\"https://example.com\">&lt;strong&gt;http&lt;/strong&gt;://example.com</a>", true)
            check(linked.text == "http://example.com")
            check(linked.links == listOf(FanfouTextLink(0, 18, "https://example.com")))
            check(linked.emphasis == listOf(FanfouTextEmphasis(0, 4)))

            val multiple = parse("&lt;em&gt;http&lt;/em&gt; 和 &lt;strong&gt;http&lt;/strong&gt;", true)
            check(multiple.text == "http 和 http")
            check(multiple.emphasis.sortedBy { it.start } == listOf(FanfouTextEmphasis(0, 4), FanfouTextEmphasis(7, 11)))

            val ordinary = parse("&lt;strong&gt;http&lt;/strong&gt;", false)
            check(ordinary.text == "<strong>http</strong>")
            check(ordinary.emphasis.isEmpty())

            val untrusted = parse("&lt;script&gt;alert(1)&lt;/script&gt; &lt;strong&gt;http&lt;/strong&gt;", true)
            check(untrusted.text == "<script>alert(1)</script> http")
            check(untrusted.emphasis == listOf(FanfouTextEmphasis(26, 30)))

            val direct = parse("<strong>http</strong> / <em>https</em>", true)
            check(direct.text == "http / https")
            check(direct.emphasis == listOf(FanfouTextEmphasis(0, 4), FanfouTextEmphasis(7, 12)))

            val mention = parse("前 @<a href=\"https://fanfou.com/miao.l\">LeeMay</a> 后", false)
            check(mention.text == "前 @LeeMay 后")
            check(mention.mentions == listOf(FanfouMention(2, 9, "miao.l")))
            check(mention.links.isEmpty())

            val innerMention = parse("<a href=\"http://fanfou.com/~abc\">@有 空格</a>", false)
            check(innerMention.mentions == listOf(FanfouMention(0, 5, "~abc")))

            val spoof = parse("@<a href=\"https://fanfou.com.evil.test/miao.l\">LeeMay</a>", false)
            check(spoof.mentions.isEmpty())

            check(FanfouApiException(403, "已向 LeeMay 发出关注请求，请等待确认。").pendingFollowRequest)
            check(!FanfouApiException(403, "无权访问").pendingFollowRequest)
            check(!FanfouApiException(500, "已向 LeeMay 发出关注请求，请等待确认。").pendingFollowRequest)
            check(!FanfouApiException(401, "已向 LeeMay 发出关注请求，请等待确认。").pendingFollowRequest)
        }
        val output = Bundle().apply {
            putString("stream", result.fold(
                { "Text and follow response checks passed 13 cases." },
                { "Search text parsing failed: ${it.stackTraceToString()}" }
            ))
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
