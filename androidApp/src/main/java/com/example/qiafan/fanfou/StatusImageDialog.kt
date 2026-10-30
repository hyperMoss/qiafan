package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.qiafan.R
import com.bumptech.glide.Glide
import com.bumptech.glide.request.FutureTarget
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class StatusImageDialog(
    private val activity: Activity,
    private val status: FanfouStatus,
    private val loadReplies: () -> List<FanfouStatus>,
    private val requestSavePermission: (callback: (Boolean) -> Unit) -> Unit,
    private val onClosed: () -> Unit,
    private val loadOriginal: ((String) -> FanfouStatus)? = null
) : Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences("image_export", Context.MODE_PRIVATE)
    private var selectedStyle = StatusImageStyle.entries.firstOrNull {
        it.name == preferences.getString("style", null)
    } ?: StatusImageStyle.PAPER
    private val toolbarColor: Int get() = when (selectedStyle) {
        StatusImageStyle.PAPER -> selectedStyle.accent
        StatusImageStyle.MINIMAL -> selectedStyle.ink
        StatusImageStyle.MIDNIGHT -> selectedStyle.paper
    }
    private val paperBackground: Int get() = selectedStyle.background
    private var loadedReplies: List<FanfouStatus>? = null
    private var loadedQuotes: Map<String, FanfouStatus> = emptyMap()
    private var stylePicker: AlertDialog? = null
    private var bitmap: Bitmap? = null
    private enum class ExportAction { SAVE, SHARE }
    private var exporting: ExportAction? = null
    private var closed = false
    private lateinit var header: LinearLayout
    private lateinit var stage: FrameLayout
    private lateinit var styleButton: TextView
    private lateinit var preview: ImageView
    private lateinit var feedback: LinearLayout
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var save: ImageButton
    private lateinit var share: LinearLayout
    private lateinit var shareFooter: LinearLayout

    private data class GeneratedImage(val bitmap: Bitmap, val replies: List<FanfouStatus>, val quotes: Map<String, FanfouStatus>)

    fun open() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paperBackground)
        }
        header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(toolbarColor)
        }
        header.addView(icon(R.drawable.ic_content_image_back, "返回") { dismiss() })
        styleButton = TextView(activity).apply {
            text = selectedStyle.title + " ▾"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true
            contentDescription = "切换分享图片风格，当前${selectedStyle.title}"
            setOnClickListener { showStylePicker() }
        }
        header.addView(styleButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        save = icon(R.drawable.ic_content_image_save, "保存到本地") {
            if (exporting == null && bitmap != null) {
                exporting = ExportAction.SAVE
                updateEnabled()
                requestSavePermission { granted ->
                    if (closed) return@requestSavePermission
                    if (granted) saveImage() else finishExport("未获得相册写入权限，可再次点按保存。")
                }
            }
        }
        header.addView(save)
        column.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        stage = FrameLayout(activity).apply { setBackgroundColor(paperBackground) }
        preview = ImageView(activity).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "内容图片预览"
        }
        stage.addView(ScrollView(activity).apply {
            setPadding(dp(10), dp(12), dp(10), dp(12))
            clipToPadding = false
            addView(preview, FrameLayout.LayoutParams(-1, -2))
        }, FrameLayout.LayoutParams(-1, -1))
        feedback = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
            elevation = dp(6).toFloat()
        }
        progress = ProgressBar(activity)
        feedback.addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)))
        message = TextView(activity).apply {
            textSize = 14f
            setTextColor(toolbarColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        feedback.addView(message)
        retry = Button(activity).apply {
            text = "重试"
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { loadAndGenerate() }
        }
        feedback.addView(retry)
        stage.addView(feedback, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(18), dp(12), dp(18), 0)
        })
        column.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        shareFooter = LinearLayout(activity).apply {
            setPadding(dp(16), dp(10), dp(16), dp(16))
            setBackgroundColor(paperBackground)
        }
        share = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = shareBackground()
            contentDescription = "分享"
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
            setOnClickListener { shareImage() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_content_image_share)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(activity).apply {
                text = "分享"
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(8), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
        shareFooter.addView(share, LinearLayout.LayoutParams(-1, dp(48)))
        column.addView(shareFooter, LinearLayout.LayoutParams(-1, -2))
        setContentView(column)
        setOnDismissListener {
            stylePicker?.dismiss()
            stylePicker = null
            closed = true
            worker.shutdownNow()
            main.removeCallbacksAndMessages(null)
            preview.setImageDrawable(null)
            bitmap = null
            loadedReplies = null
            loadedQuotes = emptyMap()
            onClosed()
        }
        show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(paperBackground))
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = toolbarColor
            navigationBarColor = paperBackground
            if (Build.VERSION.SDK_INT >= 26) decorView.systemUiVisibility =
                if (selectedStyle == StatusImageStyle.MIDNIGHT) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        loadAndGenerate()
    }

    private fun loadAndGenerate(reloadComments: Boolean = true) {
        val cachedReplies = if (reloadComments) null else loadedReplies
        val cachedQuotes = loadedQuotes
        val style = selectedStyle
        bitmap = null
        preview.setImageDrawable(null)
        showState(if (cachedReplies == null) "正在读取评论并生成图片…" else "正在切换图片风格…", busy = true)
        updateEnabled()
        worker.execute {
            val targets = mutableListOf<FutureTarget<Bitmap>>()
            val result = runCatching {
                val replies = cachedReplies ?: runCatching(loadReplies).getOrElse { error ->
                    error("评论加载失败：${reason(error)}")
                }.filter { it.replyToStatusId == status.id }.distinctBy { it.id }
                val quotes = if (cachedReplies != null) cachedQuotes else {
                    val originals = mutableMapOf(status.id to status)
                    val ids = (listOf(status) + replies).mapNotNull { statusContent(it).quoteId }
                        .filter { it.isNotBlank() && it != status.id }.distinct()
                    for (id in ids) {
                        try {
                            loadOriginal?.invoke(id)?.takeIf { it.id == id }?.let { originals[id] = it }
                        } catch (error: Exception) {
                            if (Thread.currentThread().isInterrupted || error is InterruptedException) throw error
                            // 原动态不可用时，使用转发中已有的原文或来源提示。
                        }
                    }
                    originals
                }
                val image = StatusImageRenderer.render(status, replies, style, quotes) { url, avatar ->
                    val safeUrl = FanfouApi.secureMediaUrl(url)
                    if (safeUrl == null) {
                        if (!avatar) error("配图没有可用的 HTTPS 地址")
                        null
                    } else {
                        val target = Glide.with(activity.applicationContext).asBitmap().load(safeUrl)
                            .fitCenter().submit(if (avatar) 96 else 804, if (avatar) 96 else 1600)
                        targets += target
                        try {
                            target.get(if (avatar) 5 else 20, TimeUnit.SECONDS)
                        } catch (error: Exception) {
                            if (Thread.currentThread().isInterrupted || error is InterruptedException) throw error
                            if (!avatar) error("配图加载失败，请检查网络后重试")
                            null
                        }
                    }
                }
                GeneratedImage(image, replies, quotes)
            }
            targets.forEach { Glide.with(activity.applicationContext).clear(it) }
            post {
                result.onSuccess { generated ->
                    bitmap = generated.bitmap
                    loadedReplies = generated.replies
                    loadedQuotes = generated.quotes
                    preview.setImageBitmap(generated.bitmap)
                    preview.contentDescription = "内容图片预览，${style.title}风格，含 ${generated.replies.size} 条评论"
                    feedback.visibility = View.GONE
                }.onFailure { error ->
                    showState("生成失败：${reason(error)}", retryable = true)
                }
                updateEnabled()
            }
        }
    }

    private fun showStylePicker() {
        if (bitmap == null || exporting != null || stylePicker != null) return
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        val picker = AlertDialog.Builder(activity).setTitle("分享图片风格")
            .setView(list).setNegativeButton("取消", null).create()
        stylePicker = picker
        picker.setOnDismissListener { stylePicker = null }
        StatusImageStyle.entries.forEach { style ->
            val current = style == selectedStyle
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(76)
                background = GradientDrawable().apply {
                    setColor(style.paper)
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (current) 2 else 1), if (current) Color.rgb(0, 119, 181) else Color.rgb(222, 226, 232))
                }
                isFocusable = true
                contentDescription = "选择" + style.title + "风格"
                setOnClickListener {
                    picker.dismiss()
                    if (style != selectedStyle) {
                        selectedStyle = style
                        preferences.edit().putString("style", style.name).apply()
                        styleButton.text = style.title + " ▾"
                        styleButton.contentDescription = "切换分享图片风格，当前${style.title}"
                        header.setBackgroundColor(toolbarColor)
                        stage.setBackgroundColor(paperBackground)
                        shareFooter.setBackgroundColor(paperBackground)
                        share.background = shareBackground()
                        window?.statusBarColor = toolbarColor
                        window?.navigationBarColor = paperBackground
                        if (Build.VERSION.SDK_INT >= 26) window?.decorView?.systemUiVisibility =
                            if (style == StatusImageStyle.MIDNIGHT) 0 else View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        loadAndGenerate(reloadComments = false)
                    }
                }
            }
            row.addView(TextView(activity).apply {
                text = "Aa"
                textSize = 22f
                setTextColor(style.accent)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(style.background); cornerRadius = dp(8).toFloat() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(activity).apply { text = style.title; textSize = 16f; setTextColor(style.ink) })
                addView(TextView(activity).apply { text = style.description; textSize = 12f; setTextColor(style.muted) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (current) row.addView(TextView(activity).apply { text = "✓"; textSize = 19f; setTextColor(style.accent) })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        picker.show()
    }

    private fun saveImage() {
        val image = bitmap ?: return
        showState("正在保存到本地相册…", busy = true)
        worker.execute {
            val result = runCatching {
                val id = status.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
                AlbumImageWriter(activity.applicationContext).write(
                    "fanfou_content_${id}_${System.currentTimeMillis()}.png", "image/png"
                ) { output ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码图片" }
                }
            }
            post {
                finishExport(result.fold({ "已保存到本地相册 · Pictures/恰饭" }, { "保存失败：${reason(it)}，可重试。" }))
            }
        }
    }

    private fun shareImage() {
        val image = bitmap ?: return
        if (exporting != null) return
        exporting = ExportAction.SHARE
        showState("正在准备分享图片…", busy = true)
        updateEnabled()
        worker.execute {
            val result = runCatching { StatusImageSharer(activity.applicationContext).prepare(image) }
            post {
                val outcome = result.fold({ intent ->
                    try {
                        activity.startActivity(Intent.createChooser(intent, "分享图片"))
                        "请选择应用分享图片。"
                    } catch (error: ActivityNotFoundException) {
                        "无法打开分享，请先安装支持图片分享的应用。"
                    } catch (error: Exception) {
                        "无法打开分享：${reason(error)}"
                    }
                }, { error -> "准备分享失败：${reason(error)}，可重试。" })
                finishExport(outcome)
            }
        }
    }

    private fun updateEnabled() {
        val enabled = bitmap != null && exporting == null
        save.isEnabled = enabled
        share.isEnabled = enabled
        styleButton.isEnabled = enabled
        styleButton.alpha = if (enabled) 1f else 0.5f
        save.alpha = if (enabled) 1f else 0.4f
        share.alpha = if (enabled) 1f else 0.4f
    }

    private fun showState(value: String, busy: Boolean = false, retryable: Boolean = false) {
        message.text = value
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        retry.visibility = if (retryable) View.VISIBLE else View.GONE
        feedback.visibility = View.VISIBLE
    }

    private fun finishExport(value: String) {
        exporting = null
        showState(value)
        updateEnabled()
        Toast.makeText(activity, value, Toast.LENGTH_SHORT).show()
        main.postDelayed({
            if (!closed && exporting == null && bitmap != null) feedback.visibility = View.GONE
        }, 2500)
    }

    private fun post(block: () -> Unit) {
        if (!worker.isShutdown) main.post {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) block()
        }
    }

    private fun shareBackground() = GradientDrawable().apply {
        setColor(toolbarColor)
        cornerRadius = dp(8).toFloat()
    }

    private fun icon(drawable: Int, description: String, click: () -> Unit) = ImageButton(activity).apply {
        setImageResource(drawable)
        contentDescription = description
        setPadding(dp(13), dp(13), dp(13), dp(13))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        val backgroundValue = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)
        setBackgroundResource(backgroundValue.resourceId)
        if (Build.VERSION.SDK_INT >= 26) tooltipText = description
        setOnClickListener { click() }
    }
    private fun reason(error: Throwable) = error.message?.take(100) ?: "请稍后重试"
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
