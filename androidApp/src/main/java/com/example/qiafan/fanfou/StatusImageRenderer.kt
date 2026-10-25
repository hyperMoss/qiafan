package com.example.qiafan.fanfou

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.text.SimpleDateFormat
import java.util.Locale

/** 以固定输出宽度排版，不依赖屏幕截图或当前滚动位置。 */
internal object StatusImageRenderer {
    const val WIDTH = 900
    private const val MAX_HEIGHT = 12000
    private const val GUTTER = 72f

    fun render(
        status: FanfouStatus,
        comments: List<FanfouStatus>,
        style: StatusImageStyle = StatusImageStyle.PAPER,
        quotes: Map<String, FanfouStatus> = emptyMap(),
        loadImage: (url: String, avatar: Boolean) -> Bitmap?
    ): Bitmap {
        val commands = mutableListOf<(Canvas) -> Unit>()
        val images = mutableMapOf<Pair<String, Boolean>, Bitmap?>()
        var y = GUTTER
        fun checkHeight() {
            require(y + 120 < MAX_HEIGHT) { "动态和评论过长，暂时无法生成完整图片" }
            check(!Thread.currentThread().isInterrupted) { "已取消生成" }
        }
        fun image(url: String, avatar: Boolean): Bitmap? {
            val key = url to avatar
            if (!images.containsKey(key)) images[key] = loadImage(url, avatar)
            return images[key]
        }
        fun text(
            value: String, size: Float, color: Int, bold: Boolean = false,
            left: Float = GUTTER, right: Float = WIDTH - GUTTER
        ) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, (right - left).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(9f, 1f)
                .build()
            val top = y
            commands += { canvas ->
                canvas.save()
                canvas.translate(left, top)
                layout.draw(canvas)
                canvas.restore()
            }
            y += layout.height
            checkHeight()
        }
        fun divider() {
            val top = y
            commands += { canvas ->
                canvas.drawLine(GUTTER, top, WIDTH - GUTTER, top,
                    Paint().apply { color = style.divider; strokeWidth = 2f })
            }
            y += 32
        }
        fun block(left: Float, right: Float, layout: () -> Unit) {
            val top = y
            val backgroundIndex = commands.size
            y += 24
            layout()
            y += 24
            checkHeight()
            val rect = RectF(left, top, right, y)
            commands.add(backgroundIndex) { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.contentBlock }
                canvas.drawRoundRect(rect, 14f, 14f, paint)
                paint.color = style.divider
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawRoundRect(rect, 14f, 14f, paint)
            }
            y += 24
        }
        fun identity(item: FanfouStatus, left: Float, right: Float, size: Float = 72f) {
            val top = y
            val avatar = item.user.largeAvatar.ifBlank { item.user.avatar }.takeIf { it.isNotBlank() }
                ?.let { image(it, true) }
            commands += { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = style.divider }
                val center = left + size / 2
                canvas.drawCircle(center, top + size / 2, size / 2, paint)
                if (avatar != null) {
                    canvas.save()
                    val clip = android.graphics.Path().apply {
                        addCircle(center, top + size / 2, size / 2, android.graphics.Path.Direction.CW)
                    }
                    canvas.clipPath(clip)
                    canvas.drawBitmap(avatar, null, RectF(left, top, left + size, top + size), paint)
                    canvas.restore()
                } else {
                    paint.color = style.muted
                    paint.textSize = size * 0.44f
                    paint.textAlign = Paint.Align.CENTER
                    val baseline = top + size / 2 - (paint.ascent() + paint.descent()) / 2
                    canvas.drawText(item.user.name.ifBlank { item.user.id }.take(1), center, baseline, paint)
                }
            }
            val compact = size < 72
            text(item.user.name.ifBlank { item.user.id }, if (compact) 27f else 32f,
                style.ink, true, left + size + 22, right)
            y += 9
            text("@${item.user.id} · ${date(item.createdAt)}", if (compact) 20f else 23f,
                style.muted, left = left + size + 22, right = right)
            y = maxOf(y, top + size) + if (compact) 20 else 28
        }
        fun photo(item: FanfouStatus, left: Float, right: Float) {
            val url = item.originalPhoto ?: item.photo ?: return
            val bitmap = image(url, false) ?: error("配图加载失败，请重试")
            val available = right - left
            val height = minOf(1600f, available * bitmap.height / bitmap.width)
            val width = height * bitmap.width / bitmap.height
            val imageLeft = left + (available - width) / 2
            val rect = RectF(imageLeft, y, imageLeft + width, y + height)
            commands += { canvas ->
                canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            y += height + 24
            checkHeight()
        }
        fun content(item: FanfouStatus, comment: Boolean, left: Float, right: Float) {
            identity(item, left, right, if (comment) 56f else 72f)
            val formatted = statusContent(item)
            val replyingToSharedStatus = comment && formatted.quoteId == status.id
            if (replyingToSharedStatus) {
                text("回复 @${status.user.name.ifBlank { status.user.id }}", 21f, style.muted, left = left, right = right)
                y += 14
            }
            if (formatted.text.isNotBlank()) {
                text(formatted.text, if (comment) 30f else 36f, style.ink, left = left, right = right)
                y += 24
            }
            val original = formatted.quoteId?.let { id -> quotes[id] }?.takeIf { it.id != item.id }
            val embeddedPhoto = original == null && formatted.embeddedQuote != null && item.repostId != null
            if (formatted.quoteId != null && !replyingToSharedStatus) {
                block(left, right) {
                    val innerLeft = left + 24
                    val innerRight = right - 24
                    if (original != null) {
                        identity(original, innerLeft, innerRight, 48f)
                        text(original.text.ifBlank { "（无文字）" }, 28f, style.ink, left = innerLeft, right = innerRight)
                        y += 20
                        photo(original, innerLeft, innerRight)
                    } else {
                        val caption = formatted.captionName
                        val source = when {
                            formatted.embeddedQuote != null -> caption?.let { "转自 @$it" } ?: "转发的动态"
                            caption != null -> "来自 @$caption"
                            else -> "原动态暂不可用"
                        }
                        text(source, 22f, style.accent, true, innerLeft, innerRight)
                        formatted.embeddedQuote?.let {
                            y += 16
                            text(it, 28f, style.ink, left = innerLeft, right = innerRight)
                        }
                        if (embeddedPhoto) {
                            y += 20
                            photo(item, innerLeft, innerRight)
                        }
                    }
                }
            }
            val ownPhoto = item.originalPhoto ?: item.photo
            val originalPhoto = original?.let { it.originalPhoto ?: it.photo }
            if (!embeddedPhoto && ownPhoto != null && (replyingToSharedStatus || ownPhoto != originalPhoto)) {
                photo(item, left, right)
            }
        }
        content(status, false, GUTTER, WIDTH - GUTTER)
        divider()
        if (comments.isNotEmpty()) {
            text("评论 · ${comments.size}", 27f, style.accent, true)
            y += 32
            comments.forEach { comment ->
                block(GUTTER, WIDTH - GUTTER) {
                    content(comment, true, GUTTER + 24, WIDTH - GUTTER - 24)
                }
            }
            divider()
        }
        text("饭否 · 恰饭", 23f, style.muted)
        y += GUTTER
        val bitmap = Bitmap.createBitmap(WIDTH, y.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawPage(canvas, style)
        commands.forEach { it(canvas) }
        return bitmap
    }

    private fun drawPage(canvas: Canvas, style: StatusImageStyle) {
        val paper = style.paper
        canvas.drawColor(style.background)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }
        if (style == StatusImageStyle.MINIMAL) {
            paint.color = style.accent
            canvas.drawRect(72f, 28f, 148f, 34f, paint)
            return
        }
        if (style == StatusImageStyle.MIDNIGHT) {
            canvas.drawRoundRect(RectF(20f, 20f, WIDTH - 20f, canvas.height - 20f), 24f, 24f, paint)
            paint.color = style.accent
            canvas.drawRoundRect(RectF(72f, 38f, 148f, 43f), 2.5f, 2.5f, paint)
            return
        }
        canvas.drawRect(20f, 20f, WIDTH - 20f, canvas.height - 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.rgb(222, 218, 207)
        canvas.drawRect(20f, 20f, WIDTH - 20f, canvas.height - 20f, paint)
        paint.color = Color.rgb(235, 231, 221)
        canvas.drawRect(32f, 32f, WIDTH - 32f, canvas.height - 32f, paint)
        for (x in listOf(20f, WIDTH - 20f)) {
            for (y in listOf(20f, canvas.height - 20f)) {
                paint.style = Paint.Style.FILL
                paint.color = paper
                canvas.drawRect(x - 4f, y - 4f, x + 4f, y + 4f, paint)
                paint.style = Paint.Style.STROKE
                paint.color = Color.rgb(222, 218, 207)
                canvas.drawRect(x - 4f, y - 4f, x + 4f, y + 4f, paint)
            }
        }
    }

    private fun date(raw: String): String = runCatching {
        val parsed = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(raw)
        if (parsed == null) raw else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(parsed)
    }.getOrDefault(raw)
}
