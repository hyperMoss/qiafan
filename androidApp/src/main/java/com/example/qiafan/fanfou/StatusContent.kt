package com.example.qiafan.fanfou

/** 卡片正文的展示拆解：回复剥掉 `@昵称 ` 前缀，转发剥掉 `转@昵称 ` 前缀，引用内容单独成卡。 */
internal data class StatusContent(
    val text: String,
    val links: List<FanfouTextLink> = emptyList(),
    val emphasis: List<FanfouTextEmphasis> = emptyList(),
    val mentions: List<FanfouMention> = emptyList(),
    val captionName: String? = null,
    val quoteId: String? = null,
    val embeddedQuote: String? = null
)

/**
 * 转发按 `转@昵称 ` 拆出引用内容（原文本里就带着原文），拆不出时整体保留；
 * 回复剥掉 `@昵称 ` 前缀并记下来源，富文本区间同步平移；普通动态原样展示。
 */
internal fun statusContent(status: FanfouStatus): StatusContent {
    val text = status.text
    if (status.repostId != null) {
        val marker = text.indexOf("转@")
        val prefix = if (marker >= 0) {
            Regex("^转@(\\S+?)(?:\\s*[:：]\\s*|\\s+|\$)").find(text.substring(marker))
        } else null
        if (prefix != null) {
            val cut = marker + prefix.range.last + 1
            return StatusContent(
                text = if (marker > 0) text.substring(0, marker).trim() else "",
                quoteId = status.repostId,
                captionName = prefix.groupValues[1],
                embeddedQuote = text.substring(cut).trim().ifBlank { null }
            )
        }
        return StatusContent(text = text.ifBlank { "（无文字）" }, quoteId = status.repostId)
    }
    if (status.replyToStatusId.isNotBlank()) {
        val match = Regex("^@(\\S+)\\s+").find(text)
        val cut = match?.range?.last?.plus(1) ?: 0
        return StatusContent(
            text = if (cut > 0) text.substring(cut).ifBlank { "（无文字）" } else text.ifBlank { "（无文字）" },
            links = shiftLinks(status.links, cut),
            emphasis = shiftEmphasis(status.emphasis, cut),
            mentions = shiftMentions(status.mentions, cut),
            captionName = match?.groupValues?.get(1)
                ?: status.replyToScreenName.ifBlank { status.replyToUserId }.ifBlank { null },
            quoteId = status.replyToStatusId
        )
    }
    return StatusContent(
        text = text.ifBlank { "（无文字）" },
        links = status.links,
        emphasis = status.emphasis,
        mentions = status.mentions
    )
}

/** 剥掉前缀 [cut] 个字符后平移富文本区间；落在前缀里的区间直接丢弃。 */
private fun shiftLinks(ranges: List<FanfouTextLink>, cut: Int) =
    ranges.mapNotNull { if (it.start >= cut) FanfouTextLink(it.start - cut, it.end - cut, it.url) else null }

private fun shiftEmphasis(ranges: List<FanfouTextEmphasis>, cut: Int) =
    ranges.mapNotNull { if (it.start >= cut) FanfouTextEmphasis(it.start - cut, it.end - cut) else null }

private fun shiftMentions(ranges: List<FanfouMention>, cut: Int) =
    ranges.mapNotNull { if (it.start >= cut) FanfouMention(it.start - cut, it.end - cut, it.userId) else null }

