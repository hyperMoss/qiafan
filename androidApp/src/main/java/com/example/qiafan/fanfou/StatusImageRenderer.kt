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
        loadImage: (url: String, avatar: Boolean) -> Bitmap?
    ): Bitmap {
        val ink = style.ink
        val muted = style.muted
        val commands = mutableListOf<(Canvas) -> Unit>()
        var y = GUTTER
        fun checkHeight() {
            require(y + 120 < MAX_HEIGHT) { "动态和评论过长，暂时无法生成完整图片" }
            check(!Thread.currentThread().isInterrupted) { "已取消生成" }
        }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false, left: Float = GUTTER) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, (WIDTH - left - GUTTER).toInt())
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
        fun entry(item: FanfouStatus, comment: Boolean) {
            val top = y
            val avatar = item.user.largeAvatar.ifBlank { item.user.avatar }.takeIf { it.isNotBlank() }?.let { loadImage(it, true) }
            commands += { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.divider }
                canvas.drawCircle(GUTTER + 36, top + 36, 36f, paint)
                if (avatar != null) {
                    canvas.save()
                    val clip = android.graphics.Path().apply {
                        addCircle(GUTTER + 36, top + 36, 36f, android.graphics.Path.Direction.CW)
                    }
                    canvas.clipPath(clip)
                    canvas.drawBitmap(avatar, null, RectF(GUTTER, top, GUTTER + 72, top + 72), paint)
                    canvas.restore()
                } else {
                    paint.color = muted
                    paint.textSize = 32f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(item.user.name.ifBlank { item.user.id }.take(1), GUTTER + 36, top + 47, paint)
                }
            }
            text(item.user.name.ifBlank { item.user.id }, 32f, ink, true, GUTTER + 94)
            y += 9
            text("@${item.user.id} · ${date(item.createdAt)}", 23f, muted, left = GUTTER + 94)
            y = maxOf(y, top + 72) + 28
            text(item.text.ifBlank { "（无文字）" }, if (comment) 30f else 36f, ink)
            y += 26
            (item.originalPhoto ?: item.photo)?.let { url ->
                val bitmap = loadImage(url, false) ?: error("配图加载失败，请重试")
                val available = WIDTH - GUTTER * 2
                val height = minOf(1600f, available * bitmap.height / bitmap.width)
                val width = height * bitmap.width / bitmap.height
                val left = (WIDTH - width) / 2
                val rect = RectF(left, y, left + width, y + height)
                commands += { canvas ->
                    canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                y += height + 28
                checkHeight()
            }
        }
        entry(status, false)
        if (comments.isNotEmpty()) {
            divider()
            text("评论 · ${comments.size}", 27f, style.accent, true)
            y += 32
            comments.forEach { comment ->
                entry(comment, true)
                divider()
            }
        } else divider()
        text("饭否 · 恰饭", 23f, muted)
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
