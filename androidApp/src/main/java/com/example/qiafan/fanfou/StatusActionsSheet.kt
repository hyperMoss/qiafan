package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser

internal enum class StatusQuickAction(val title: String, val hint: String, val path: String) {
    FAVORITE("收藏", "保存到我的收藏", "M12,21C-8,9 4,-3 12,6C20,-3 32,9 12,21Z"),
    REPLY("写评论", "回复这条动态", "M21,15Q21,18 18,18H9L3,22V6Q3,3 6,3H18Q21,3 21,6ZM7,8H17M7,12H14"),
    REPOST("转发", "编辑转发内容后发布", "M8,6H20M16,2L20,6L16,10M20,6V16M16,18H4M8,14L4,18L8,22M4,18V8"),
    QUICK_REPOST("一键快转", "直接转发到我的时间线", "M13,2L4,14H11L10,22L20,9H13Z"),
    CREATE_IMAGE("生成图片", "包含全部可见评论 · 多种风格", "M4,3H20V21H4ZM7,15L11,11L17,17M15,7L16,7"),
    COPY("复制正文", "复制这条动态的文字", "M8,8H21V21H8ZM16,8V3H3V16H8"),
    VIEW_PHOTO("查看原图", "打开完整图片", "M4,3H20V21H4ZM7,16L11,12L17,18M15,7L16,7"),
    SAVE_PHOTO("保存原图", "保留原始分辨率", "M12,3V16M7,11L12,16L17,11M4,17V21H20V17"),
    DELETE("删除这条动态", "删除前需要再次确认", "M3,6H21M9,6V3H15V6M6,6L7,21H17L18,6M10,10V17M14,10V17")
}

internal class StatusActionsSheet(
    private val activity: Activity,
    private val status: FanfouStatus,
    private val favorited: Boolean,
    private val ownsStatus: Boolean,
    private val onClosed: () -> Unit = {},
    private val onAction: (StatusQuickAction) -> Unit
) : Dialog(activity) {
    private val ink = Color.rgb(31, 36, 42)
    private val muted = Color.rgb(107, 113, 121)
    private val accent = Color.rgb(0, 119, 181)
    private val danger = Color.rgb(194, 45, 53)

    fun open() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val contentActions = mutableListOf(StatusQuickAction.CREATE_IMAGE, StatusQuickAction.COPY)
        if (status.photo != null || status.originalPhoto != null) contentActions += StatusQuickAction.VIEW_PHOTO
        if (status.originalPhoto != null) contentActions += StatusQuickAction.SAVE_PHOTO
        val sections = mutableListOf(
            "互动" to listOf(StatusQuickAction.FAVORITE, StatusQuickAction.REPLY, StatusQuickAction.REPOST, StatusQuickAction.QUICK_REPOST),
            "图片与内容" to contentActions.toList()
        )
        if (ownsStatus) sections += "" to listOf(StatusQuickAction.DELETE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 24)
            clipToOutline = true
        }
        val handle = FrameLayout(activity).apply {
            contentDescription = "关闭快速操作"
            isFocusable = true
            setOnClickListener { dismiss() }
            addView(View(activity).apply { background = rounded(Color.rgb(214, 220, 225), 2) },
                FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        var dragStart = 0f
        handle.setOnTouchListener { view, event ->
            val distance = (event.rawY - dragStart).coerceAtLeast(0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { root.animate().cancel(); dragStart = event.rawY }
                MotionEvent.ACTION_MOVE -> root.translationY = distance
                MotionEvent.ACTION_UP -> {
                    if (distance > dp(48)) dismiss() else {
                        root.animate().translationY(0f).setDuration(150).start()
                        if (kotlin.math.abs(event.rawY - dragStart) < dp(8)) view.performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> root.animate().translationY(0f).setDuration(150).start()
            }
            true
        }
        root.addView(handle, LinearLayout.LayoutParams(-1, dp(28)))
        root.addView(text("动态操作", 19, ink, true).apply { setPadding(dp(22), 0, dp(22), 0) })
        root.addView(text(status.user.name.ifBlank { status.user.id } + " · " + status.text, 12, muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(22), dp(6), dp(22), dp(12))
        })
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        sections.forEach { (title, actions) ->
            if (title.isNotEmpty()) list.addView(text(title, 11, muted, true).apply {
                setPadding(dp(22), dp(10), dp(22), dp(4))
            }) else list.addView(View(activity).apply { setBackgroundColor(Color.rgb(234, 237, 240)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(22), dp(10), dp(22), dp(6)) })
            actions.forEach { list.addView(actionRow(it)) }
        }
        root.addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("取消", 15, ink, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(245, 247, 248), 12)
            isFocusable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(18), dp(10), dp(18), dp(14)) })
        setContentView(root)
        setOnDismissListener { onClosed() }
        setCanceledOnTouchOutside(true)
        show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
            navigationBarColor = Color.WHITE
            val desiredHeight = dp(212 + sections.sumOf { it.second.size } * 64)
            setLayout(-1, minOf(desiredHeight, (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()))
        }
    }

    private fun actionRow(action: StatusQuickAction): View {
        val selected = action == StatusQuickAction.FAVORITE && favorited
        val title = if (selected) "取消收藏" else action.title
        val hint = if (selected) "从我的收藏中移除" else action.hint
        val color = if (action == StatusQuickAction.DELETE) danger else accent
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(Color.rgb(232, 242, 248)), null, null)
            isFocusable = true
            contentDescription = "$title，$hint"
            setOnClickListener { dismiss(); onAction(action) }
        }
        row.addView(ActionIcon(activity, action, color).apply {
            background = rounded(if (action == StatusQuickAction.DELETE) Color.rgb(255, 239, 240) else Color.rgb(237, 245, 250), 11)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(text(title, 15, if (action == StatusQuickAction.DELETE) danger else ink, true))
            addView(text(hint, 12, muted).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private class ActionIcon(context: Context, action: StatusQuickAction, color: Int) : View(context) {
        private val path = PathParser.createPathFromPathData(action.path)
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height) * 0.54f
            canvas.save()
            canvas.translate((width - size) / 2, (height - size) / 2)
            canvas.scale(size / 24f, size / 24f)
            path?.let { canvas.drawPath(it, pen) }
            canvas.restore()
        }
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
