package com.example.qiafan.fanfou

import android.app.Activity
import android.app.Dialog
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
    private val onClosed: () -> Unit
) : Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val toolbarColor = Color.rgb(116, 99, 82)
    private val paperBackground = Color.rgb(235, 225, 202)
    private var bitmap: Bitmap? = null
    private enum class ExportAction { SAVE, SHARE }
    private var exporting: ExportAction? = null
    private var closed = false
    private lateinit var preview: ImageView
    private lateinit var feedback: LinearLayout
    private lateinit var message: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: Button
    private lateinit var save: ImageButton
    private lateinit var share: ImageButton

    private data class GeneratedImage(val bitmap: Bitmap, val commentCount: Int)

    fun open() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(paperBackground)
        }
        val header = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(toolbarColor)
        }
        header.addView(icon(R.drawable.ic_content_image_back, "返回") { dismiss() })
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
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
        share = icon(R.drawable.ic_content_image_share, "分享") { shareImage() }
        header.addView(save)
        header.addView(share)
        column.addView(header, LinearLayout.LayoutParams(-1, dp(52)))

        val stage = FrameLayout(activity)
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
        setContentView(column)
        setOnDismissListener {
            closed = true
            worker.shutdownNow()
            main.removeCallbacksAndMessages(null)
            preview.setImageDrawable(null)
            bitmap = null
            onClosed()
        }
        show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(paperBackground))
            decorView.setPadding(0, 0, 0, 0)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = toolbarColor
            navigationBarColor = paperBackground
            if (Build.VERSION.SDK_INT >= 26) decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        loadAndGenerate()
    }

    private fun loadAndGenerate() {
        bitmap = null
        preview.setImageDrawable(null)
        showState("正在读取评论并生成图片…", busy = true)
        updateEnabled()
        worker.execute {
            val targets = mutableListOf<FutureTarget<Bitmap>>()
            val result = runCatching {
                val replies = runCatching(loadReplies).getOrElse { error ->
                    error("评论加载失败：${reason(error)}")
                }.filter { it.replyToStatusId == status.id }.distinctBy { it.id }
                val image = StatusImageRenderer.render(status, replies) { url, avatar ->
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
                GeneratedImage(image, replies.size)
            }
            targets.forEach { Glide.with(activity.applicationContext).clear(it) }
            post {
                result.onSuccess { generated ->
                    bitmap = generated.bitmap
                    preview.setImageBitmap(generated.bitmap)
                    preview.contentDescription = "内容图片预览，含 ${generated.commentCount} 条评论"
                    feedback.visibility = View.GONE
                }.onFailure { error ->
                    showState("生成失败：${reason(error)}", retryable = true)
                }
                updateEnabled()
            }
        }
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
