package com.example.qiafan.fanfou

import org.junit.Assert.*
import org.junit.Test

class RepostQuoteTest {
    private val source = FanfouStatus("root", "原文内容还有下一句", "", FanfouUser("root-user", "原作者", "", false), false, null, null, null)

    @Test fun aDeepOriginalCannotReplaceAnIntermediateComment() {
        val content = StatusContent("我的评论", captionName = "中间作者", quoteId = source.id,
            embeddedQuote = "上一人的评论 转@原作者 原文内容")
        assertNull(quotedStatus(content, source))
        assertSame(source, embeddedQuotedStatus(content, source))
        assertEquals(listOf(RepostQuotePart("中间作者", "上一人的评论"), RepostQuotePart("原作者", "原文内容")),
            repostQuoteParts(content.captionName, content.embeddedQuote!!))
    }

    @Test fun directOriginalCanExpandTruncatedQuotedText() {
        val content = StatusContent("", captionName = source.user.name, embeddedQuote = "原文内容")
        assertSame(source, quotedStatus(content, source))
    }

    @Test fun repeatedAuthorsStillKeepTheirDifferentComments() {
        val content = StatusContent("", captionName = source.user.name,
            embeddedQuote = "同一个人的新评论 转@原作者 原文内容")
        assertNull(quotedStatus(content, source))
        assertEquals(2, repostQuoteParts(content.captionName, content.embeddedQuote!!).size)
    }

    @Test fun punctuationAndEmptyRepostsKeepEveryAttribution() {
        assertEquals(listOf(RepostQuotePart("甲", ""), RepostQuotePart("乙", ""), RepostQuotePart("丙", "末尾正文")),
            repostQuoteParts("甲", "转@乙：转@丙 末尾正文"))
    }

    @Test fun unavailableOriginalKeepsTheEmbeddedChain() {
        val content = StatusContent("", captionName = "甲", embeddedQuote = "甲的评论 转@乙 乙的正文")
        assertNull(quotedStatus(content, null))
        assertNull(embeddedQuotedStatus(content, null))
        assertEquals(listOf("甲的评论", "乙的正文"), repostQuoteParts(content.captionName, content.embeddedQuote!!).map { it.text })
    }
}
