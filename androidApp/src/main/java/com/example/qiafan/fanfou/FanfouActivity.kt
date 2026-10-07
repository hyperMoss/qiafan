package com.example.qiafan.fanfou

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.util.Log
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/** Android-first Fanfou client. The existing Kuikly sample remains available as a separate Activity. */
class FanfouActivity : AppCompatActivity() {
    // Android equivalents of the exported prototype's editorial color tokens.
    private val ink = Color.rgb(31, 36, 42)
    private val muted = Color.rgb(107, 113, 121)
    private val accent = Color.rgb(0, 119, 181)
    private val canvas = Color.rgb(249, 250, 251)
    private val border = Color.rgb(229, 232, 235)
    private val subtle = Color.rgb(245, 247, 248)
    private val danger = Color.rgb(194, 45, 53)

    private lateinit var root: FrameLayout
    private lateinit var session: SecureSession
    private lateinit var api: FanfouApi
    private lateinit var body: LinearLayout
    private val io = Executors.newFixedThreadPool(3)
    private var activeTab = "关注"
    private var statusLoader: ((String?) -> List<FanfouStatus>)? = null
    private var nextCursor: (List<FanfouStatus>, String?) -> String? =
        { received, _ -> received.lastOrNull()?.id }
    private var items = mutableListOf<FanfouStatus>()
    private var nextId: String? = null
    private var homeItems: List<FanfouStatus> = emptyList()
    private var homeNextId: String? = null
    private var homeScrollY = 0
    private var lastFollowTabTapAt = 0L
    private var busy = false
    private var showingTabs = false
    private var exploreSection = "热门话题"
    private var currentUser: FanfouUser? = null
    private var currentUserLoading = false
    private var navAvatarFrame: FrameLayout? = null
    private var drawerAvatarFrame: FrameLayout? = null
    private val favoriteState = mutableMapOf<String, Boolean>()
    private val favoriteInFlight = mutableSetOf<String>()
    private var generation = 0
    private var pendingSave: FanfouStatus? = null
    private var notificationSnapshot: NotificationSnapshot? = null
    private var trendsSnapshot: List<FanfouTrend>? = null
    private var unreadCounts: FanfouCounts? = null
    private var notificationSection = "提及"
    private var notificationBadge: TextView? = null
    private var currentScroll: ScrollView? = null
    private var loadingHost: FrameLayout? = null
    private var loadingOverlay: View? = null
    private val repostInFlight = mutableSetOf<String>()
    private val replyInFlight = mutableSetOf<String>()
    private val deleteInFlight = mutableSetOf<String>()
    private val followRequestInFlight = mutableSetOf<String>()

    companion object {
        private const val STATUS_SENT = "sent"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_FAILED = "failed"
    }
    private var timelineProfile: FanfouUser? = null
    private var chatPeer: FanfouUser? = null
    private var commentTarget: FanfouStatus? = null
    private var replyList: List<FanfouStatus> = emptyList()
    private var replyState: String? = null
    private var replyError: String = ""
    private var chatLog: LinearLayout? = null
    private val chatLines = mutableListOf<ChatLine>()
    private var currentPage: (() -> Unit)? = null
    private val backStack = ArrayDeque<() -> Unit>()
    private var restoringPage = false

    private data class NotificationSnapshot(
        val counts: FanfouCounts,
        val mentions: Result<List<FanfouStatus>>,
        val conversations: Result<List<FanfouConversation>>,
        val requests: Result<List<FanfouUser>>
    )

    /** 会话页里的一条消息。状态在本地推进，key 用于发送中/失败后重发同一行。 */
    private class ChatLine(
        val key: String,
        val text: String,
        val mine: Boolean,
        val time: String,
        val day: String,
        var status: String,
        var reason: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        session = SecureSession(this)
        api = FanfouApi { session.credentials() ?: error("请先授权饭否账号") }
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        if (session.credentials()?.token?.isNotBlank() == true) showTab("关注") else showLogin()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (root.childCount > 1) {
            root.removeViewAt(root.childCount - 1)
        } else if (backStack.isNotEmpty()) {
            val previous = backStack.removeLast()
            restoringPage = true
            try { previous() } finally { restoringPage = false }
        } else if (!showingTabs && session.credentials()?.token?.isNotBlank() == true) {
            showTab(activeTab)
        } else {
            super.onBackPressed()
        }
    }

    private fun enterPage(reopen: () -> Unit) {
        rememberHomeState()
        if (!restoringPage) currentPage?.let { backStack.addLast(it) }
        currentPage = reopen
    }

    private fun rememberHomeState() {
        if (showingTabs && activeTab == "关注" && statusLoader != null) {
            homeItems = items.toList()
            homeNextId = nextId
            homeScrollY = currentScroll?.scrollY ?: 0
        }
    }

    private fun showLogin() {
        generation++
        statusLoader = null
        currentUser = null
        currentUserLoading = false
        navAvatarFrame = null
        drawerAvatarFrame = null
        currentPage = null
        backStack.clear()
        unreadCounts = null
        notificationSnapshot = null
        trendsSnapshot = null
        homeItems = emptyList()
        homeNextId = null
        homeScrollY = 0
        items.clear()
        nextId = null
        favoriteState.clear()
        favoriteInFlight.clear()
        repostInFlight.clear()
        replyInFlight.clear()
        deleteInFlight.clear()
        followRequestInFlight.clear()
        chatPeer = null
        chatLog = null
        chatLines.clear()
        commentTarget = null
        replyList = emptyList()
        replyState = null
        replyError = ""
        root.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(canvas) }
        val stage = FrameLayout(this)
        val column = box().apply {
            setPadding(dp(28), dp(72), dp(28), dp(28))
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(stage)
        stage.addView(column, FrameLayout.LayoutParams(
            dp(minOf(460, resources.configuration.screenWidthDp)), -1, Gravity.CENTER_HORIZONTAL
        ))
        root.addView(scroll)
        column.addView(label("恰饭", 42, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        column.addView(label("把时间留给文字。", 16, muted).apply { top(8) })
        column.addView(divider().apply { top(32) })
        column.addView(label("登录饭否", 22, ink, true).apply { top(32) })
        column.addView(label("使用饭否用户名和密码登录，继续阅读关注的动态。", 14, muted).apply { top(8) })
        val username = field("饭否用户名或邮箱").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = field("饭否密码").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(username.apply { top(28) })
        column.addView(password.apply { top(12) })
        lateinit var loginButton: TextView
        loginButton = action("登录") {
            val loginName = username.text.toString().trim()
            val loginPassword = password.text.toString()
            if (loginName.isBlank() || loginPassword.isBlank()) {
                toast("请填写用户名和密码")
                return@action
            }
            password.text.clear()
            loginButton.isEnabled = false
            loginButton.text = "正在登录…"
            runIo(
                {
                    val token = api.xauth(loginName, loginPassword)
                    val authorized = NofanApplication.credentials(token)
                    FanfouApi { authorized }.verify()
                    session.save(authorized)
                },
                {
                    showTab("关注")
                },
                { error ->
                    loginButton.isEnabled = true
                    loginButton.text = "登录"
                    toast("登录失败：" + safeMessage(error))
                }
            )
        }
        column.addView(loginButton.apply {
            top(20)
            setTextColor(Color.WHITE)
            background = rounded(accent, 12)
        })
        column.addView(label("密码仅用于通过 HTTPS 换取访问令牌，不会保存在本机。", 12, muted).apply { top(16) })
    }

    private fun showTab(tab: String) {
        if (tab == "热门" && !session.showHot) return
        rememberHomeState()
        backStack.clear()
        currentPage = { showTab(tab) }
        activeTab = tab
        when (tab) {
            "关注" -> showTimeline("关注", true) { api.home(it) }
            "通知" -> showNotifications()
            "热门" -> showTrends()
        }
        if (tab != "通知") refreshUnreadCounts()
        ensureCurrentUser()
    }

    private fun ensureCurrentUser() {
        if (currentUser != null || currentUserLoading) return
        currentUserLoading = true
        runIo(
            { api.verify() },
            { user ->
                currentUserLoading = false
                currentUser = user
                navAvatarFrame?.let { renderAvatar(it, user, 44) }
                drawerAvatarFrame?.let { renderAvatar(it, user, 64) }
            },
            { currentUserLoading = false }
        )
    }

    private fun refreshUnreadCounts() {
        val turn = generation
        runIo(
            { api.counts() },
            { counts ->
                if (turn != generation) return@runIo
                unreadCounts = counts
                updateUnreadBadge()
            },
            { /* The content request reports its own errors; keep an unknown badge hidden. */ }
        )
    }

    private fun updateUnreadBadge() {
        val total = unreadCounts?.let { it.mentions + it.messages + it.requests } ?: 0
        notificationBadge?.apply {
            text = total.toString()
            visibility = if (total > 0) View.VISIBLE else View.GONE
        }
    }

    /**
     * 搭一页界面。[header] 固定在标题栏下方、[footer] 固定在底部，两者都不随内容滚动，
     * 会话页用它把「对方资料」和「输入栏」钉在消息列表的两端。
     */
    private fun shell(title: String, tabs: Boolean, header: View? = null, footer: View? = null) {
        generation++
        showingTabs = tabs
        root.removeAllViews()
        val page = box().apply { setBackgroundColor(Color.WHITE) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val contentWidth = dp(minOf(720, resources.configuration.screenWidthDp))
        val barWrap = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        page.addView(barWrap, LinearLayout.LayoutParams(-1, dp(56)))
        val bar = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.WHITE)
        }
        barWrap.addView(bar, FrameLayout.LayoutParams(contentWidth, dp(56), Gravity.CENTER))
        if (tabs) bar.addView(avatarFrame(currentUser, 44).apply {
            navAvatarFrame = this
            contentDescription = "打开导航栏"
            setOnClickListener { drawer() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        else bar.addView(icon("‹") {
            onBackPressed()
        })
        val heading = label(title, 21, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        }
        bar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        if (tabs && activeTab == "关注") bar.addView(iconButton("search", "搜索动态与用户") { showSearch() })
        val headerDivider = divider()
        page.addView(headerDivider)
        header?.let {
            page.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        val content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        loadingHost = content
        loadingOverlay = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.WHITE)
        }
        currentScroll = scroll
        body = box().apply { setPadding(0, 0, 0, dp(24)) }
        scroll.addView(body)
        content.addView(scroll, FrameLayout.LayoutParams(contentWidth, -1, Gravity.CENTER))
        var previousY = 0
        var directionTravel = 0
        var headerHidden = false
        var ignoreHeaderLayoutScroll = false
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val dy = y - previousY
            val containsMessages = statusLoader != null || (tabs && activeTab == "通知")
            if (containsMessages && !ignoreHeaderLayoutScroll) {
                directionTravel = when {
                    dy > 0 -> directionTravel.coerceAtLeast(0) + dy
                    dy < 0 -> directionTravel.coerceAtMost(0) + dy
                    else -> directionTravel
                }
                val shouldHide = when {
                    y <= dp(8) -> false
                    directionTravel >= dp(24) -> true
                    directionTravel <= -dp(24) -> false
                    else -> headerHidden
                }
                if (shouldHide != headerHidden) {
                    headerHidden = shouldHide
                    ignoreHeaderLayoutScroll = true
                    barWrap.visibility = if (shouldHide) View.GONE else View.VISIBLE
                    headerDivider.visibility = if (shouldHide) View.GONE else View.VISIBLE
                    directionTravel = 0
                    scroll.post {
                        previousY = scroll.scrollY
                        directionTravel = 0
                        ignoreHeaderLayoutScroll = false
                    }
                }
            }
            previousY = y
            if (statusLoader != null && !busy && nextId != null &&
                y + scroll.height >= body.height - dp(160)
            ) loadStatuses(false)
        }
        var touchStart = 0f
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touchStart = if (scroll.scrollY == 0) event.rawY else 0f
                MotionEvent.ACTION_UP -> if (touchStart > 0 && event.rawY - touchStart > dp(100)) {
                    when {
                        statusLoader != null -> loadStatuses(true)
                        tabs && activeTab == "通知" -> showNotifications()
                        tabs && activeTab == "热门" -> showTrends()
                    }
                }
            }
            false
        }
        if (tabs) {
            val nav = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), 0, dp(10), 0)
            }
            page.addView(divider())
            val navWrap = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
            page.addView(navWrap, LinearLayout.LayoutParams(-1, dp(64)))
            navWrap.addView(nav, FrameLayout.LayoutParams(contentWidth, dp(64), Gravity.CENTER))
            listOf("关注", "通知", "热门").filter { it != "热门" || session.showHot }.forEach { name ->
                val selected = name == activeTab
                val item = box().apply {
                    gravity = Gravity.CENTER
                    contentDescription = name + if (selected) "，当前页面" else ""
                    isFocusable = true
                    setOnClickListener {
                        if (!selected) {
                            showTab(name)
                        } else if (name == "关注" && activeTab == "关注") {
                            val now = SystemClock.uptimeMillis()
                            val doubleTap = lastFollowTabTapAt > 0 &&
                                now - lastFollowTabTapAt <= ViewConfiguration.getDoubleTapTimeout()
                            lastFollowTabTapAt = now
                            if (doubleTap) {
                                lastFollowTabTapAt = 0L
                                loadStatuses(true)
                            } else {
                                currentScroll?.smoothScrollTo(0, 0)
                            }
                        } else {
                            currentScroll?.smoothScrollTo(0, 0)
                        }
                    }
                }
                val symbol = when (name) { "关注" -> "home"; "通知" -> "bell"; else -> "trend" }
                val icon = SymbolIcon(symbol, if (selected) ink else muted)
                val iconFrame = FrameLayout(this)
                item.addView(iconFrame, LinearLayout.LayoutParams(dp(54), dp(30)))
                iconFrame.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                val caption = label(name, 11, if (selected) ink else muted, selected).apply {
                    gravity = Gravity.CENTER
                    top(2)
                }
                item.addView(caption)
                if (name == "通知") {
                    val count = unreadCounts?.let {
                        it.mentions + it.messages + it.requests
                    } ?: 0
                    notificationBadge = label(count.toString(), 10, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        minWidth = dp(16)
                        minHeight = dp(16)
                        setPadding(dp(3), 0, dp(3), 0)
                        background = rounded(danger, 9)
                        visibility = if (count > 0) View.VISIBLE else View.GONE
                    }
                    val badgeParams = FrameLayout.LayoutParams(-2, dp(16), Gravity.TOP or Gravity.END)
                    iconFrame.addView(notificationBadge, badgeParams)
                }
                nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            }
        } else notificationBadge = null
        footer?.let {
            page.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        if (tabs && activeTab == "关注") {
            val compose = label("+", 30, Color.WHITE).apply {
                gravity = Gravity.CENTER
                contentDescription = "发布动态"
                background = rounded(accent, 16)
                elevation = dp(7).toFloat()
                setOnClickListener { showCompose() }
            }
            content.addView(compose, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(((resources.configuration.screenWidthDp - 720).coerceAtLeast(0) / 2) + 18)
                bottomMargin = dp(18)
            })
        }
    }

    private fun showTimeline(
        title: String,
        tabs: Boolean = false,
        cursorForNext: (List<FanfouStatus>, String?) -> String? =
            { received, _ -> received.lastOrNull()?.id },
        profile: FanfouUser? = null,
        loader: (String?) -> List<FanfouStatus>
    ) {
        if (!tabs) enterPage { showTimeline(title, tabs, cursorForNext, profile, loader) }
        statusLoader = loader
        timelineProfile = profile
        nextCursor = cursorForNext
        items.clear()
        nextId = null
        val restoreHome = tabs && title == "关注" && homeItems.isNotEmpty()
        if (restoreHome) {
            items.addAll(homeItems)
            nextId = homeNextId
        }
        busy = false
        shell(title, tabs)
        if (restoreHome) {
            renderStatuses(nextId != null)
            currentScroll?.post { currentScroll?.scrollTo(0, homeScrollY) }
        } else loadStatuses(true)
    }

    private fun loadStatuses(reset: Boolean) {
        val loader = statusLoader ?: return
        if (busy) return
        busy = true
        val turn = generation
        if (reset) {
            if (items.isEmpty()) {
                body.removeAllViews()
                timelineProfile?.let { body.addView(profileHeader(it)) }
                if (showingTabs && activeTab == "热门" && exploreSection == "随便看看") {
                    addExploreHeader()
                }
                body.addView(loadingPlaceholders())
            }
        }
        showFloatingLoading(if (reset) "正在读取动态…" else "正在加载更多…")
        val cursor = if (reset) null else nextId
        runIo(
            { loader(cursor) },
            { received ->
                if (turn != generation) return@runIo
                busy = false
                hideFloatingLoading()
                if (reset) {
                    items.clear()
                    nextId = null
                }
                val known = items.mapTo(mutableSetOf()) { it.id }
                val fresh = received.filter { it.id.isNotBlank() && known.add(it.id) }
                items.addAll(fresh)
                nextId = if (received.size >= 20 && fresh.isNotEmpty()) {
                    nextCursor(received, cursor)
                } else null
                if (showingTabs && activeTab == "关注") {
                    homeItems = items.toList()
                    homeNextId = nextId
                }
                renderStatuses(nextId != null)
            },
            { error ->
                if (turn != generation) return@runIo
                busy = false
                hideFloatingLoading()
                if (items.isEmpty()) {
                    body.removeAllViews()
                    timelineProfile?.let { body.addView(profileHeader(it)) }
                    if (showingTabs && activeTab == "热门" && exploreSection == "随便看看") {
                        addExploreHeader()
                    }
                    val denied = timelineProfile?.protected == true &&
                        (error as? FanfouApiException)?.code == 403
                    body.addView(emptyState(
                        if (denied) "无权查看此用户的动态" else "暂时无法读取动态",
                        if (denied) "该账号的可见范围由饭否服务器决定。" else safeMessage(error)
                    ))
                }
                body.addView(errorRow("加载失败：" + safeMessage(error)) { loadStatuses(reset) })
            }
        )
    }

    private fun renderStatuses(hasMore: Boolean) {
        val scroll = body.parent as? ScrollView
        val previousY = scroll?.scrollY ?: 0
        body.removeAllViews()
        timelineProfile?.let { body.addView(profileHeader(it)) }
        if (showingTabs && activeTab == "热门" && exploreSection == "随便看看") addExploreHeader()
        if (items.isEmpty()) body.addView(emptyState("这里还没有动态", "下拉刷新，稍后再来看看。"))
        items.forEach { body.addView(statusCard(it)) }
        if (hasMore) body.addView(label("继续下滑，加载更多", 12, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(18), dp(16), dp(18))
        })
        scroll?.post { scroll.scrollTo(0, previousY) }
    }

    private fun statusCard(status: FanfouStatus, detail: Boolean = false): View {
        favoriteState.putIfAbsent(status.id, status.favorited)
        val card = box().apply {
            setPadding(dp(18), dp(16), dp(18), 0)
            setBackgroundColor(Color.WHITE)
            if (!detail) setOnClickListener { openStatus(status) }
        }
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(avatarFrame(status.user, 40), LinearLayout.LayoutParams(dp(40), dp(40)))
        val identity = box().apply { setPadding(dp(10), 0, 0, 0) }
        val names = row().apply { gravity = Gravity.CENTER_VERTICAL }
        names.addView(label(status.user.name.ifBlank { status.user.id }, 15, ink, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        names.addView(label("  @" + status.user.id, 12, muted).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        identity.addView(names)
        identity.addView(label(relativeTime(status.createdAt), 11, muted).apply { top(2) })
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        header.setOnClickListener { openUser(status.user) }
        if (!detail) header.addView(label("⋯", 24, muted).apply {
            gravity = Gravity.CENTER
            contentDescription = "更多动态操作"
            isFocusable = true
            setOnClickListener { showStatusActions(status) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(header)
        card.addView(label(status.text.ifBlank { "（无文字）" }, 16, ink).apply {
            top(10)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        status.photo?.let { url ->
            val photoFrame = FrameLayout(this).apply {
                background = rounded(subtle, 8)
                clipToOutline = true
                contentDescription = "${status.user.name}发布的图片，点开查看原图"
                setOnClickListener { showPhoto(status) }
            }
            val photo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(subtle, 8)
                clipToOutline = true
            }
            photoFrame.addView(photo, FrameLayout.LayoutParams(-1, -1))
            val progress = ProgressBar(this).apply { isIndeterminate = true }
            photoFrame.addView(progress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            val state = label("", 13, muted).apply {
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            photoFrame.addView(state, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            card.addView(photoFrame, LinearLayout.LayoutParams(-1, dp(220)).apply {
                topMargin = dp(10)
            })
            Glide.with(this).load(url).listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
                ): Boolean {
                    recordImageFailure("photo", url, e)
                    progress.visibility = View.GONE
                    state.text = "图片加载失败 · 点按查看"
                    state.visibility = View.VISIBLE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>,
                    dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean
                ): Boolean {
                    progress.visibility = View.GONE
                    state.visibility = View.GONE
                    val availableWidth = dp((minOf(720, resources.configuration.screenWidthDp) - 36).coerceAtLeast(1))
                    val originalWidth = resource.intrinsicWidth.coerceAtLeast(1)
                    val originalHeight = resource.intrinsicHeight.coerceAtLeast(1)
                    val naturalHeight = (availableWidth.toDouble() * originalHeight / originalWidth).toInt()
                    val imageHeight = naturalHeight.coerceIn(1, dp(340))
                    val imageWidth = if (naturalHeight > dp(340)) {
                        (dp(340).toDouble() * originalWidth / originalHeight).toInt()
                            .coerceIn(1, availableWidth)
                    } else availableWidth
                    photoFrame.layoutParams = (photoFrame.layoutParams as LinearLayout.LayoutParams).apply {
                        height = imageHeight.coerceAtLeast(dp(100))
                    }
                    photo.layoutParams = FrameLayout.LayoutParams(imageWidth, imageHeight, Gravity.CENTER)
                    photoFrame.background = rounded(Color.WHITE, 8)
                    return false
                }
            }).into(photo)
        }
        if (!detail) {
            card.setOnLongClickListener { showStatusActions(status); true }
            card.addView(divider().apply { top(14) })
            return card
        }
        card.addView(divider().apply { top(14) })
        val actions = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        val favorite = smallAction(if (favoriteState[status.id] == true) "♥ 已收藏" else "♡ 收藏") {
            toggleFavorite(status, it)
        }
        val repostButton = smallAction("↗ 快转") { repost(status, it) }
        actions.addView(repostButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(favorite, LinearLayout.LayoutParams(0, dp(44), 1f))
        if (status.originalPhoto != null) actions.addView(smallAction("↓ 原图") {
            saveOriginal(status)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        card.addView(actions)
        card.addView(divider())
        return card
    }

    private fun showStatusActions(status: FanfouStatus) {
        val names = mutableListOf("查看详情", if (favoriteState[status.id] == true) "取消收藏" else "收藏", "一键快转", "写评论")
        if (ownsStatus(status)) names += "删除这条动态"
        if (status.photo != null || status.originalPhoto != null) names += "查看原图"
        if (status.originalPhoto != null) names += "保存原图"
        names += "复制正文"
        AlertDialog.Builder(this).setTitle("动态操作").setItems(names.toTypedArray()) { _, index ->
            when (names[index]) {
                "查看详情" -> openStatus(status)
                "写评论" -> openStatus(status)
                "收藏", "取消收藏" -> toggleFavorite(status, null)
                "一键快转" -> repost(status, null)
                "删除这条动态" -> confirmDeleteStatus(status)
                "查看原图" -> showPhoto(status)
                "保存原图" -> saveOriginal(status)
                "复制正文" -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("动态正文", status.text))
                    toast("已复制正文")
                }
            }
        }.show()
    }

    /** 删除入口只对当前账号发布的列表动态开放，服务端仍是最终权限判定方。 */
    private fun ownsStatus(status: FanfouStatus): Boolean =
        currentUser?.id?.takeIf { it.isNotBlank() } == status.user.id

    private fun confirmDeleteStatus(status: FanfouStatus) {
        if (!ownsStatus(status)) {
            toast("只能删除自己发布的动态")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除动态")
            .setMessage("删除后无法恢复，确定删除这条动态吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteStatus(status) }
            .show()
    }

    private fun deleteStatus(status: FanfouStatus) {
        if (!ownsStatus(status)) {
            toast("只能删除自己发布的动态")
            return
        }
        if (!deleteInFlight.add(status.id)) return
        val turn = generation
        showFloatingLoading("正在删除动态…")
        runIo(
            { api.deleteStatus(status.id) },
            {
                deleteInFlight.remove(status.id)
                if (turn != generation) return@runIo
                items.removeAll { it.id == status.id }
                homeItems = homeItems.filterNot { it.id == status.id }
                replyList = replyList.filterNot { it.id == status.id }
                if (statusLoader != null) renderStatuses(nextId != null)
                hideFloatingLoading()
                toast("已删除动态")
            },
            { error ->
                deleteInFlight.remove(status.id)
                if (turn != generation) return@runIo
                hideFloatingLoading()
                toast("删除失败：" + safeMessage(error))
            }
        )
    }

    private fun showPhoto(status: FanfouStatus) {
        val url = status.originalPhoto ?: status.photo ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        val photo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${status.user.name}的图片"
            setPadding(dp(8), dp(56), dp(8), dp(72))
        }
        overlay.addView(photo, FrameLayout.LayoutParams(-1, -1))
        Glide.with(this).load(url).into(photo)
        val top = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(0x99000000.toInt())
        }
        overlay.addView(top, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        top.addView(label("×", 30, Color.WHITE).apply {
            gravity = Gravity.CENTER
            contentDescription = "关闭图片"
            setOnClickListener { root.removeView(overlay) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(label(status.user.name, 15, Color.WHITE, true).apply {
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (status.originalPhoto != null) top.addView(label("保存原图", 13, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setOnClickListener { saveOriginal(status) }
        })
    }

    private fun toggleFavorite(status: FanfouStatus, button: TextView?) {
        if (!favoriteInFlight.add(status.id)) return
        val target = !(favoriteState[status.id] ?: status.favorited)
        button?.isEnabled = false
        runIo(
            { api.setFavorite(status.id, target) },
            { updated ->
                favoriteInFlight.remove(status.id)
                favoriteState[status.id] = updated.favorited
                val index = items.indexOfFirst { it.id == status.id }
                if (index >= 0) items[index] = updated
                button?.apply {
                    text = if (updated.favorited) "♥ 已收藏" else "♡ 收藏"
                    isEnabled = true
                }
                toast(if (updated.favorited) "已收藏" else "已取消收藏")
            },
            { error ->
                favoriteInFlight.remove(status.id)
                button?.isEnabled = true
                toast("收藏失败：" + safeMessage(error))
            }
        )
    }

    private fun repost(status: FanfouStatus, button: TextView?) {
        if (!repostInFlight.add(status.id)) return
        button?.isEnabled = false
        runIo(
            { api.repost(status) },
            {
                repostInFlight.remove(status.id)
                button?.isEnabled = true
                toast("已快转")
            },
            { error ->
                repostInFlight.remove(status.id)
                button?.isEnabled = true
                toast("快转失败：" + safeMessage(error))
            }
        )
    }

    private fun openUser(user: FanfouUser) {
        if (user.id.isBlank()) return
        session.remember(HistoryEntry("user", user.id, user.name))
        showTimeline(user.name.ifBlank { "@" + user.id }, profile = user) {
            api.userTimeline(user.id, it)
        }
        currentPage = { openUser(user) }
        val turn = generation
        runIo(
            { api.showUser(user.id) },
            { full ->
                if (turn != generation || timelineProfile?.id != user.id) return@runIo
                timelineProfile = full
                val first = body.getChildAt(0)
                if (first?.tag == "profile-header") {
                    body.removeViewAt(0)
                    body.addView(profileHeader(full), 0)
                }
            },
            { /* Keep the summary already included in the timeline response. */ }
        )
    }

    /** 详情页的正文和评论上下文。评论写入 `in_reply_to_*`，读取走上下文接口过滤。 */
    private data class DetailPayload(
        val status: FanfouStatus,
        val replies: Result<List<FanfouStatus>>
    )

    /**
     * 动态详情页。评论读取走 `context_timeline` + `in_reply_to_status_id` 过滤
     * （官方没有 comments 端点）；正文与评论一起拉，任一失败都只影响自己那一块。
     */
    private fun openStatus(status: FanfouStatus) {
        session.remember(HistoryEntry("status", status.id, status.text.take(32)))
        enterPage { openStatus(status) }
        statusLoader = null
        timelineProfile = null
        items.clear()
        commentTarget = status
        replyList = emptyList()
        replyState = null
        replyError = ""
        shell("动态详情", false, footer = replyComposer(status))
        renderStatusDetail(status)
        val turn = generation
        showFloatingLoading("正在加载动态…")
        runIo(
            { DetailPayload(api.showStatus(status.id), runCatching { api.replies(status.id) }) },
            { payload ->
                if (turn != generation || commentTarget?.id != status.id) return@runIo
                hideFloatingLoading()
                items = mutableListOf(payload.status)
                payload.replies.onSuccess { list ->
                    replyList = list
                    replyState = "ok"
                }.onFailure { error ->
                    replyList = emptyList()
                    replyState = "failed"
                    replyError = safeMessage(error)
                }
                renderStatusDetail(payload.status)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                body.addView(errorRow("动态更新失败：" + safeMessage(error)) { openStatus(status) })
            }
        )
    }

    /** 详情页整体重绘：正文 + 回复上下文 + 评论列表（读取失败时给出真实原因）。 */
    private fun renderStatusDetail(status: FanfouStatus) {
        body.removeAllViews()
        body.addView(statusCard(status, detail = true))
        replyHint(status)?.let { body.addView(it) }
        val head = row().apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(22), dp(18), dp(10))
        }
        head.addView(label("评论", 19, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (replyState == "ok") {
            head.addView(label(replyList.size.toString() + " 条", 11, muted).apply {
                typeface = Typeface.MONOSPACE
            })
        }
        body.addView(head)
        when (replyState) {
            "ok" -> if (replyList.isEmpty()) {
                body.addView(emptyState("还没有评论", "回复会显示在这里。"))
            } else {
                replyList.forEach { body.addView(commentRow(it, status)) }
            }
            "failed" -> body.addView(
                emptyState("评论加载失败", replyError.ifBlank { "请稍后重试" })
            )
            else -> body.addView(loadingPlaceholders())
        }
    }

    /** 一条回复，连同本详情页已加载的完整原动态一起展示。 */
    private fun commentRow(comment: FanfouStatus, original: FanfouStatus): View = box().apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        setBackgroundColor(Color.WHITE)
        val line = row().apply { gravity = Gravity.TOP }
        addView(line)
        line.addView(avatarFrame(comment.user, 36).apply {
            isClickable = true
            contentDescription = "打开${comment.user.name.ifBlank { comment.user.id }}的主页"
            setOnClickListener { openUser(comment.user) }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val text = box().apply { setPadding(dp(12), 0, 0, 0) }
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val names = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "打开${comment.user.name.ifBlank { comment.user.id }}的主页"
            setOnClickListener { openUser(comment.user) }
        }
        text.addView(names)
        val who = comment.user.name.ifBlank { comment.user.id }
        val mine = currentUser?.id?.isNotBlank() == true && comment.user.id == currentUser?.id
        names.addView(label(if (mine) "$who · 我" else who, 14, ink, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        names.addView(label(relativeTime(comment.createdAt), 11, muted).apply {
            typeface = Typeface.MONOSPACE
        })
        val contextUsers = listOf(comment.user, original.user)
        text.addView(mentionLabel(
            comment.text.ifBlank { "（无文字）" },
            14,
            ink,
            knownUsers = contextUsers
        ).apply {
            top(5)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        comment.photo?.let {
            text.addView(inlinePhoto(comment, 150), LinearLayout.LayoutParams(-1, dp(150)).apply {
                topMargin = dp(10)
            })
        }
        text.addView(originalStatusCard(original, contextUsers), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        addView(divider())
    }

    /** 评论下方的原动态保留作者、时间、完整正文和图片；卡片空白处可进入动态详情。 */
    private fun originalStatusCard(
        original: FanfouStatus,
        contextUsers: List<FanfouUser>
    ): View = box().apply {
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = rounded(subtle, 8)
        isClickable = original.id.isNotBlank()
        isFocusable = original.id.isNotBlank()
        contentDescription = "查看被回复的原动态"
        if (original.id.isNotBlank()) setOnClickListener { openStatusById(original.id) }

        val title = row().apply { gravity = Gravity.CENTER_VERTICAL }
        title.addView(label("原动态", 11, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(label("查看详情 ›", 11, accent))
        addView(title)

        val author = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
            isClickable = true
            contentDescription = "打开${original.user.name.ifBlank { original.user.id }}的主页"
            setOnClickListener { openUser(original.user) }
        }
        author.addView(avatarFrame(original.user, 30), LinearLayout.LayoutParams(dp(30), dp(30)))
        val identity = box().apply { setPadding(dp(8), 0, 0, 0) }
        identity.addView(label(original.user.name.ifBlank { original.user.id }, 13, ink, true))
        identity.addView(mentionLabel(
            "@${original.user.id}",
            11,
            muted,
            knownUsers = contextUsers
        ).apply {
            typeface = Typeface.MONOSPACE
            top(1)
        })
        author.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        author.addView(label(relativeTime(original.createdAt), 10, muted).apply {
            typeface = Typeface.MONOSPACE
        })
        addView(author)

        addView(mentionLabel(
            original.text.ifBlank { "（无文字）" },
            13,
            ink,
            knownUsers = contextUsers
        ).apply {
            top(9)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        original.photo?.let {
            addView(inlinePhoto(original, 190), LinearLayout.LayoutParams(-1, dp(190)).apply {
                topMargin = dp(10)
            })
        }
    }

    /** 评论和嵌套原动态共用的单图预览；FIT_CENTER 保证图片内容不被裁切。 */
    private fun inlinePhoto(status: FanfouStatus, heightDp: Int): View = FrameLayout(this).apply {
        background = rounded(Color.WHITE, 8)
        clipToOutline = true
        isClickable = true
        contentDescription = "${status.user.name.ifBlank { status.user.id }}发布的图片，点开查看原图"
        setOnClickListener { showPhoto(status) }
        val image = ImageView(this@FanfouActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = rounded(Color.WHITE, 8)
        }
        addView(image, FrameLayout.LayoutParams(-1, dp(heightDp)))
        Glide.with(this@FanfouActivity).load(status.photo).into(image)
    }

    /** 把饭否正文里的 @用户名 变成可点击的主页入口；显示名也可映射到已加载用户。 */
    private fun mentionLabel(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        knownUsers: List<FanfouUser> = emptyList()
    ): TextView = label(text, size, color, bold).apply {
        val richText = SpannableString(text)
        val mentions = Regex("@([\\p{L}\\p{N}_~-]+)").findAll(text).toList()
        mentions.forEach { match ->
            val userId = match.groupValues[1]
            richText.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val known = (knownUsers + listOfNotNull(currentUser)).firstOrNull {
                        it.id.equals(userId, ignoreCase = true) || it.name == userId
                    }
                    openUser(known ?: FanfouUser(userId, userId, "", false))
                }

                override fun updateDrawState(drawState: TextPaint) {
                    drawState.color = accent
                    drawState.isUnderlineText = false
                }
            }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        this.text = richText
        if (mentions.isNotEmpty()) {
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
    }

    /** 这条动态本身是回复时，标出它在回谁，避免脱离上下文。 */
    private fun replyHint(status: FanfouStatus): View? {
        val target = status.replyToStatusId.takeIf { it.isNotBlank() } ?: return null
        val who = status.replyToScreenName.ifBlank {
            status.replyToUserId.ifBlank { "上一条动态" }
        }
        return row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(subtle)
            addView(label("回复", 11, muted).apply { typeface = Typeface.MONOSPACE })
            addView(label(who, 12, ink).apply {
                setPadding(dp(8), 0, dp(8), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("查看原动态", 12, accent).apply {
                minHeight = dp(44)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, 0, 0)
                setOnClickListener { openStatusById(target) }
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
    }

    private fun openStatusById(id: String) {
        if (id.isBlank()) return
        showFloatingLoading("正在加载动态…")
        val turn = generation
        runIo(
            { api.showStatus(id) },
            { fresh ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                openStatus(fresh)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                toast("打开失败：" + safeMessage(error))
            }
        )
    }

    /** 详情页底部固定的写评论栏。 */
    private fun replyComposer(target: FanfouStatus): View = box().apply {
        val prefixName = target.user.id
        setBackgroundColor(Color.WHITE)
        addView(divider())
        val bar = row().apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(10), dp(18), dp(14))
        }
        addView(bar)
        // 服务端要求正文自带 `@对方id `，这段前缀由客户端补，长度要占掉输入额度。
        val reserved = api.replyPrefixLength(target)
        val maxBodyLength = (140 - reserved).coerceAtLeast(0)
        val input = EditText(this@FanfouActivity).apply {
            hint = if (maxBodyLength > 0) "回复 @$prefixName…" else "无法回复这条动态"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(maxBodyLength))
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 10, border)
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val sendIcon = SymbolIcon("send", muted)
        val send = FrameLayout(this@FanfouActivity).apply {
            contentDescription = "发送评论"
            isFocusable = true
            background = rounded(subtle, 22)
            addView(sendIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) }
            isEnabled = false
            isClickable = false
        }
        bar.addView(send)
        val counter = label("", 11, muted).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        addView(counter)

        var submitting = false

        fun refresh() {
            val length = input.text.toString().trim().length
            val ready = length > 0 && maxBodyLength > 0 && !submitting
            send.isEnabled = ready
            send.isClickable = ready
            send.background = rounded(if (ready) accent else subtle, 22)
            sendIcon.tint(if (ready) Color.WHITE else muted)
            val left = maxBodyLength - length
            counter.text = if (maxBodyLength == 0) {
                "无法回复：原作者标识过长"
            } else if (reserved > 0) {
                "回复 @$prefixName · 还可输入 $left 字"
            } else {
                "还可输入 $left 字"
            }
        }

        fun submit() {
            val text = input.text.toString().trim()
            if (text.isBlank() || maxBodyLength == 0 || submitting) return
            submitting = true
            input.isEnabled = false
            input.text.clear()
            refresh()
            sendReply(target, text) {
                submitting = false
                input.isEnabled = true
                input.setText(text)
                input.setSelection(input.text.length)
                refresh()
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }
        refresh()
    }

    private fun sendReply(target: FanfouStatus, text: String, onFailure: (() -> Unit)? = null) {
        if (!replyInFlight.add(target.id)) return
        val turn = generation
        showFloatingLoading("正在发送评论…")
        runIo(
            { api.reply(text, target) },
            { fresh ->
                replyInFlight.remove(target.id)
                if (turn != generation) return@runIo
                hideFloatingLoading()
                toast("评论已发出")
                val current = commentTarget
                if (current != null && current.id == target.id) {
                    // 服务端返回的这条就是回复本身，直接落到列表顶部。
                    replyList = listOf(fresh) + replyList.filterNot { it.id == fresh.id }
                    replyState = "ok"
                    renderStatusDetail(current)
                    currentScroll?.post { currentScroll?.fullScroll(View.FOCUS_DOWN) }
                }
                refreshUnreadCounts()
            },
            { error ->
                replyInFlight.remove(target.id)
                if (turn != generation) return@runIo
                hideFloatingLoading()
                onFailure?.invoke()
                // 失败不自动重发；编辑框会恢复原文，弹窗提供显式重试。
                showReplyFailure(target, text, safeMessage(error))
            }
        )
    }

    private fun showReplyFailure(target: FanfouStatus, text: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("评论发送失败")
            .setMessage(reason + "\n\n「" + text + "」")
            .setNegativeButton("关闭", null)
            .setPositiveButton("再试一次") { _, _ -> sendReply(target, text) }
            .show()
    }

    private fun showNotifications() {
        statusLoader = null
        timelineProfile = null
        items.clear()
        shell("通知", true)
        notificationSnapshot?.let { renderNotifications(it) } ?: body.addView(loadingPlaceholders())
        showFloatingLoading("正在读取通知…")
        val turn = generation
        runIo(
            {
                val counts = api.counts()
                val mentions = runCatching { api.mentions() }
                val conversations = runCatching { api.conversations() }
                val requests = runCatching { api.followRequests() }
                NotificationSnapshot(counts, mentions, conversations, requests)
            },
            { snapshot ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                notificationSnapshot = snapshot
                unreadCounts = snapshot.counts
                updateUnreadBadge()
                renderNotifications(snapshot)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                if (notificationSnapshot == null) {
                    body.removeAllViews()
                    body.addView(emptyState("通知加载失败", safeMessage(error)))
                }
                body.addView(errorRow("请检查网络后重试") { showNotifications() })
            }
        )
    }

    private fun renderNotifications(snapshot: NotificationSnapshot) {
        body.removeAllViews()
        val tabs = row().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.WHITE, 28, border)
        }
        val tabParams = LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(14), dp(12), dp(10))
        }
        body.addView(tabs, tabParams)
        listOf(
            Triple("提及", "@我的", snapshot.counts.mentions),
            Triple("私信", "私信", snapshot.counts.messages),
            Triple("关注请求", "关注请求", snapshot.counts.requests)
        ).forEach { (key, title, count) ->
            val selected = notificationSection == key
            val item = label(title + if (count > 0) "  $count" else "", 13,
                if (selected) ink else muted, selected).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                background = if (selected) rounded(subtle, 24) else rounded(Color.WHITE, 24)
                contentDescription = "$title，未读$count" + if (selected) "，当前分类" else ""
                setOnClickListener {
                    notificationSection = key
                    renderNotifications(snapshot)
                    currentScroll?.scrollTo(0, 0)
                }
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        when (notificationSection) {
            "提及" -> snapshot.mentions.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无提及与回复", "有新消息时会出现在这里。"))
                list.forEach { body.addView(statusCard(it)) }
            }.onFailure { body.addView(emptyState("提及加载失败", safeMessage(it))) }
            "私信" -> snapshot.conversations.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无私信", "收到私信后会出现在这里。"))
                list.forEach { conversation ->
                    body.addView(dmRow(conversation))
                }
            }.onFailure { body.addView(emptyState("私信加载失败", safeMessage(it))) }
            else -> snapshot.requests.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无关注请求", "新的关注请求会出现在这里。"))
                list.forEach { user ->
                    body.addView(followRequestRow(user))
                }
            }.onFailure { body.addView(emptyState("关注请求加载失败", safeMessage(it))) }
        }
    }

    private fun showTrends() {
        if (!session.showHot) return
        exploreSection = "热门话题"
        statusLoader = null
        timelineProfile = null
        items.clear()
        shell("热门", true)
        loadTrends()
    }

    private fun switchExploreSection(section: String) {
        if (exploreSection == section) return
        generation++
        exploreSection = section
        statusLoader = null
        busy = false
        items.clear()
        nextId = null
        currentScroll?.scrollTo(0, 0)
        if (section == "随便看看") {
            statusLoader = { cursor -> api.publicTimeline(cursor) }
            nextCursor = { received, _ -> received.lastOrNull()?.id }
            loadStatuses(true)
        } else showTrends()
    }

    private fun addExploreHeader() {
        val search = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), 0)
        }
        body.addView(search)
        val query = field("搜索动态或话题")
        search.addView(query, LinearLayout.LayoutParams(0, dp(50), 1f))
        search.addView(label("搜索", 14, accent, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(64)
            minHeight = dp(48)
            setOnClickListener { searchPublic(query.text.toString()) }
        })
        val recent = session.recentSearches()
        if (recent.isNotEmpty()) {
            val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; top(14) }
            body.addView(heading)
            heading.addView(label("最近搜索", 14, ink, true).apply {
                setPadding(dp(18), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label("清除", 13, muted).apply {
                setPadding(dp(16), dp(10), dp(18), dp(10))
                setOnClickListener {
                    session.clearRecentSearches()
                    if (exploreSection == "热门话题") loadTrends() else renderStatuses(nextId != null)
                }
            })
            recent.forEach { term ->
                body.addView(label(term, 14, muted).apply {
                    minHeight = dp(38)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(18), 0)
                    setOnClickListener { searchPublic(term) }
                })
            }
        }
        val tabs = row().apply {
            setPadding(dp(18), dp(12), dp(18), dp(5))
        }
        body.addView(tabs)
        listOf("热门话题", "随便看看").forEach { name ->
            val selected = exploreSection == name
            tabs.addView(label(name, 15, if (selected) accent else muted, selected).apply {
                gravity = Gravity.CENTER
                minHeight = dp(46)
                contentDescription = name + if (selected) "，当前分类" else "，打开分类"
                setOnClickListener { switchExploreSection(name) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }
        body.addView(divider())
    }

    private fun searchPublic(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return toast("请输入搜索词")
        session.rememberSearch(query)
        showTimeline("搜索 · " + query) { api.publicSearch(query, it) }
    }

    private fun loadTrends() {
        if (body.childCount == 0) {
            trendsSnapshot?.let { renderTrends(it) } ?: run {
                addExploreHeader()
                body.addView(loadingPlaceholders())
            }
        }
        showFloatingLoading("正在读取热门话题…")
        val turn = generation
        runIo(
            { api.trends() },
            { trends ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                trendsSnapshot = trends
                renderTrends(trends)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                if (trendsSnapshot == null) {
                    body.removeAllViews()
                    addExploreHeader()
                    body.addView(emptyState("热门话题加载失败", safeMessage(error)))
                }
                body.addView(errorRow("请检查网络后重试") { loadTrends() })
            }
        )
    }

    private fun renderTrends(trends: List<FanfouTrend>) {
        body.removeAllViews()
        addExploreHeader()
        body.addView(sectionHeader("正在讨论"))
        body.addView(label("选择话题，查看相关动态。", 13, muted).apply {
            setPadding(dp(18), 0, dp(18), dp(10))
        })
        if (trends.isEmpty()) body.addView(emptyState("暂时没有热门话题", "稍后再来看看。"))
        trends.forEach { trend ->
            body.addView(infoRow("# " + trend.name, "查看话题动态  →") {
                showTimeline("热门 · " + trend.name) { api.publicSearch(trend.query, it) }
            })
        }
    }

    /**
     * 私信会话页：顶部固定对方资料，中间可滚动消息流，底部固定输入栏。
     * 发送中的气泡先落屏，成功后转正，失败后给重发入口。
     */
    private fun openConversation(user: FanfouUser) {
        enterPage { openConversation(user) }
        statusLoader = null
        timelineProfile = null
        items.clear()
        chatPeer = user
        chatLines.clear()
        shell("私信", false, chatPeerHeader(user), chatComposer(user))
        body.setPadding(dp(18), dp(14), dp(18), dp(14))
        val log = box()
        chatLog = log
        body.addView(log)
        renderChatLog()
        showFloatingLoading("正在读取私信…")
        val turn = generation
        runIo(
            { api.conversation(user.id) },
            { received ->
                if (turn != generation || chatPeer?.id != user.id) return@runIo
                hideFloatingLoading()
                chatLines.clear()
                received.asReversed().forEach { message ->
                    chatLines += ChatLine(
                        key = message.id.ifBlank { "m${message.createdAt}${message.text.hashCode()}" },
                        text = message.text,
                        // 发送者缺失时按对方处理，避免把未知来源错标成自己发出的。
                        mine = message.sender.id.isNotBlank() && message.sender.id != user.id,
                        time = chatClock(message.createdAt),
                        day = chatDay(message.createdAt),
                        status = STATUS_SENT
                    )
                }
                renderChatLog()
                markConversationRead(user)
            },
            { error ->
                if (turn != generation || chatPeer?.id != user.id) return@runIo
                hideFloatingLoading()
                val log = chatLog ?: return@runIo
                log.removeAllViews()
                log.addView(emptyState("私信加载失败", safeMessage(error)))
                log.addView(errorRow("请检查网络后重试") { openConversation(user) })
            }
        )
    }

    private fun chatPeerHeader(user: FanfouUser): View = box().apply {
        setBackgroundColor(Color.WHITE)
        addView(
            row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }.apply {
                addView(avatarFrame(user, 40), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(box().apply {
                    setPadding(dp(12), 0, 0, 0)
                    addView(label(user.name.ifBlank { user.id }, 16, ink, true))
                    addView(label(
                        "@" + user.id + if (user.protected) " · 受保护用户" else "",
                        12, muted
                    ).apply { typeface = Typeface.MONOSPACE; top(3) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        )
        addView(divider())
    }

    private fun chatComposer(user: FanfouUser): View = box().apply {
        setBackgroundColor(Color.WHITE)
        addView(divider())
        val bar = row().apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(10), dp(18), dp(14))
        }
        addView(bar)
        val input = EditText(this@FanfouActivity).apply {
            hint = "输入私信内容…"
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            filters = arrayOf(InputFilter.LengthFilter(140))
            minLines = 1
            maxLines = 4
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 10, border)
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val sendIcon = SymbolIcon("send", muted)
        val send = FrameLayout(this@FanfouActivity).apply {
            contentDescription = "发送私信"
            isFocusable = true
            background = rounded(subtle, 22)
            addView(sendIcon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) }
            isEnabled = false
            isClickable = false
        }
        bar.addView(send)

        fun refresh() {
            val ready = input.text.toString().isNotBlank()
            send.isEnabled = ready
            send.isClickable = ready
            send.background = rounded(if (ready) accent else subtle, 22)
            sendIcon.tint(if (ready) Color.WHITE else muted)
        }

        fun submit() {
            val text = input.text.toString().trim()
            if (text.isBlank()) return
            val now = fanfouNow()
            val line = ChatLine(
                key = "local-" + System.currentTimeMillis(),
                text = text,
                mine = true,
                time = chatClock(now),
                day = chatDay(now),
                status = STATUS_PENDING
            )
            chatLines += line
            input.text.clear()
            refresh()
            renderChatLog()
            dispatchChat(user, line)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }
        refresh()
    }

    private fun dispatchChat(user: FanfouUser, line: ChatLine) {
        val turn = generation
        runIo(
            { api.sendDirectMessage(user.id, line.text) },
            {
                if (turn != generation) return@runIo
                line.status = STATUS_SENT
                line.reason = ""
                renderChatLog()
                refreshUnreadCounts()
            },
            { error ->
                if (turn != generation) return@runIo
                line.status = STATUS_FAILED
                line.reason = safeMessage(error)
                renderChatLog()
                toast("发送失败：" + safeMessage(error))
            }
        )
    }

    private fun renderChatLog() {
        val log = chatLog ?: return
        log.removeAllViews()
        if (chatLines.isEmpty()) {
            log.addView(emptyState("还没有消息", "发一条私信，对方回复后会出现在这里。"))
            return
        }
        var lastDay: String? = null
        var lastMine: Boolean? = null
        chatLines.forEach { line ->
            if (line.day != lastDay) {
                lastDay = line.day
                lastMine = null
                log.addView(box().apply {
                    gravity = Gravity.CENTER
                    addView(label(line.day, 11, muted).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(dp(10), dp(4), dp(10), dp(4))
                        background = rounded(subtle, 12)
                    })
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
                })
            }
            log.addView(chatBubble(line))
            val notes = mutableListOf<String>()
            if (line.mine != lastMine) notes += line.time
            if (line.mine && line.status == STATUS_PENDING) notes += "发送中…"
            if (notes.isNotEmpty()) log.addView(chatMeta(notes.joinToString(" · "), line.mine))
            if (line.mine && line.status == STATUS_FAILED) log.addView(chatFailRow(line))
            lastMine = line.mine
        }
        currentScroll?.post { currentScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatBubble(line: ChatLine): View = box().apply {
        gravity = if (line.mine) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        val stroke = when {
            line.status == STATUS_FAILED -> danger
            line.mine.not() -> border
            else -> null
        }
        val fill = when (line.status) {
            STATUS_PENDING -> muted
            STATUS_FAILED -> Color.WHITE
            else -> if (line.mine) ink else Color.WHITE
        }
        val foreground = when (line.status) {
            STATUS_PENDING, STATUS_SENT -> if (line.mine) Color.WHITE else ink
            else -> ink
        }
        addView(label(line.text, 15, foreground).apply {
            setPadding(dp(14), dp(11), dp(14), dp(11))
            maxWidth = dp(280)
            background = chatBubbleBackground(fill, line.mine, stroke)
        })
    }

    private fun chatMeta(text: String, mine: Boolean): View = box().apply {
        gravity = if (mine) Gravity.END else Gravity.START
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(10)
        }
        addView(label(text, 11, muted).apply { typeface = Typeface.MONOSPACE })
    }

    private fun chatFailRow(line: ChatLine): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        minimumHeight = dp(44)
        layoutParams = LinearLayout.LayoutParams(-1, dp(44))
        addView(label(
            "发送失败 · " + line.reason.ifBlank { "未知错误" },
            11, danger
        ).apply { typeface = Typeface.MONOSPACE })
        addView(label("重发", 13, danger, true).apply {
            minHeight = dp(44)
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "重发这条私信"
            setOnClickListener { retryChat(line) }
        })
    }

    private fun retryChat(line: ChatLine) {
        val user = chatPeer ?: return
        line.status = STATUS_PENDING
        line.reason = ""
        renderChatLog()
        dispatchChat(user, line)
    }

    /** 通知页「私信」列表的一行会话：头像 + 昵称 + 最后一条预览 + 未读标记。 */
    private fun dmRow(conversation: FanfouConversation): View = box().apply {
        setBackgroundColor(Color.WHITE)
        val who = conversation.peer.name.ifBlank { conversation.peer.id }
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            isFocusable = true
            contentDescription = "打开与 $who 的会话" + if (conversation.unread) "，有未读私信" else ""
            setOnClickListener { openConversation(conversation.peer) }
        }
        addView(line)
        line.addView(avatarFrame(conversation.peer, 40), LinearLayout.LayoutParams(dp(40), dp(40)))
        val text = box().apply { setPadding(dp(12), 0, dp(10), 0) }
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        text.addView(top)
        top.addView(
            label(who, 15, ink, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(label(chatClock(conversation.lastAt), 11, muted).apply {
            typeface = Typeface.MONOSPACE
        })
        text.addView(label(
            (if (conversation.lastFromMe) "我：" else "") +
                conversation.lastText.ifBlank { "还没有消息" },
            13, muted
        ).apply {
            top(4)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        // 会话级未读只有 new_conv 布尔值，不编造具体条数。
        if (conversation.unread) {
            line.addView(View(this@FanfouActivity).apply {
                background = rounded(accent, 5)
                contentDescription = "有未读私信"
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
        }
        addView(divider())
    }

    /** 打开会话即视为已读，同步本机会话快照并重新拉取计数。 */
    private fun markConversationRead(user: FanfouUser) {
        val snapshot = notificationSnapshot ?: return
        val updated = snapshot.conversations.getOrNull()?.map {
            if (it.peer.id == user.id) it.copy(unread = false) else it
        } ?: return
        notificationSnapshot = snapshot.copy(conversations = Result.success(updated))
        refreshUnreadCounts()
    }

    private fun chatBubbleBackground(color: Int, mine: Boolean, stroke: Int?): GradientDrawable {
        val large = dp(14).toFloat()
        val small = dp(4).toFloat()
        // 自己发的收右上角，对方发的收左上角。
        val radii = if (mine) {
            floatArrayOf(large, large, small, large, large, large, large, large)
        } else {
            floatArrayOf(small, large, large, large, large, large, large, large)
        }
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = radii
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun parseFanfouTime(value: String): Long? = runCatching {
        SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(value)?.time
    }.getOrNull()

    private fun fanfouNow(): String =
        SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).format(System.currentTimeMillis())

    private fun chatDay(value: String): String {
        val parsed = parseFanfouTime(value) ?: return ""
        val then = Calendar.getInstance().apply { timeInMillis = parsed }
        val today = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, today) -> "今天"
            sameDay(then, yesterday) -> "昨天"
            then.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日", Locale.getDefault()).format(parsed)
            else -> SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(parsed)
        }
    }

    private fun chatClock(value: String): String {
        val parsed = parseFanfouTime(value) ?: return value
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsed)
        return when (chatDay(value)) {
            "今天" -> clock
            "昨天" -> "昨天 $clock"
            else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(parsed)
        }
    }

    private fun drawer() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()) }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.setOnClickListener { root.removeView(overlay) }
        val panel = box().apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(18), dp(38), dp(18), dp(20))
            setOnClickListener { }
        }
        overlay.addView(panel, FrameLayout.LayoutParams(dp(300), -1, Gravity.START))
        val accountRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        panel.addView(accountRow)
        accountRow.addView(avatarFrame(currentUser, 64, true).apply {
            drawerAvatarFrame = this
            contentDescription = "当前账号头像"
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        val accountText = box().apply { setPadding(dp(14), 0, 0, 0) }
        accountRow.addView(accountText)
        accountText.addView(label(currentUser?.name?.ifBlank { "恰饭" } ?: "恰饭", 22, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        accountText.addView(label(currentUser?.id?.let { "@$it" } ?: "把时间留给文字", 13, muted).apply {
            top(5)
        })
        panel.addView(divider().apply { top(30) })
        listOf("个人主页", "搜索", "浏览历史", "收藏", "刷新当前页", "设置").forEach { name ->
            panel.addView(menuEntry(name) {
                root.removeView(overlay)
                when (name) {
                    "搜索" -> showSearch()
                    "浏览历史" -> showHistory()
                    "个人主页" -> currentUser?.let { openUser(it) }
                        ?: runIo({ api.verify() }, { currentUser = it; openUser(it) },
                            { toast("个人资料加载失败：" + safeMessage(it)) })
                    "收藏" -> showTimeline(
                        "收藏",
                        cursorForNext = { _, cursor ->
                            ((cursor?.toIntOrNull() ?: 1) + 1).toString()
                        }
                    ) { cursor -> api.favorites(cursor?.toIntOrNull() ?: 1) }
                    "刷新当前页" -> when (activeTab) {
                        "关注" -> if (statusLoader != null) loadStatuses(true) else showTab("关注")
                        "通知" -> showNotifications()
                        "热门" -> showTrends()
                    }
                    "设置" -> showSettings()
                }
            })
        }
    }

    private fun showSearch() {
        enterPage { showSearch() }
        statusLoader = null
        items.clear()
        shell("搜索", false)
        body.setPadding(dp(18), dp(20), dp(18), dp(24))
        body.addView(label("搜索动态与用户", 25, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        val query = field("搜索关键词")
        val user = field("指定用户 ID")
        body.addView(query.apply { top(18) })
        val scopeRow = row().apply { top(18) }
        body.addView(scopeRow)
        var scope = "全站动态"
        val scopeButtons = mutableListOf<TextView>()
        listOf("全站动态", "用户", "指定用户内").forEach { name ->
            val button = label(name, 13, if (name == scope) ink else muted, name == scope).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                background = if (name == scope) rounded(subtle, 22) else rounded(Color.WHITE, 22)
                setOnClickListener {
                    scope = name
                    user.visibility = if (scope == "指定用户内") View.VISIBLE else View.GONE
                    scopeButtons.forEachIndexed { index, chip ->
                        val selected = listOf("全站动态", "用户", "指定用户内")[index] == scope
                        chip.setTextColor(if (selected) ink else muted)
                        chip.setTypeface(chip.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                        chip.background = if (selected) rounded(subtle, 22) else rounded(Color.WHITE, 22)
                    }
                }
            }
            scopeButtons += button
            scopeRow.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        body.addView(user.apply { top(12); visibility = View.GONE })
        body.addView(action("搜索") {
            val q = query.text.toString().trim()
            val id = user.text.toString().trim()
            if (q.isBlank()) return@action toast("请输入关键词")
            if (scope == "指定用户内" && id.isBlank()) return@action toast("请填写用户 ID")
            session.rememberSearch(q)
            when (scope) {
                "全站动态" -> showTimeline("搜索 · " + q) { api.publicSearch(q, it) }
                "指定用户内" -> {
                    showTimeline("@" + id + " 内搜索") { api.userSearch(id, q, it) }
                }
                else -> searchUsers(q)
            }
        }.apply {
            top(18)
            setTextColor(Color.WHITE)
            background = rounded(accent, 12)
        })
        body.addView(divider().apply { top(28) })
        body.addView(label("直接打开用户时间线", 17, ink, true).apply { top(22) })
        val directUser = field("用户 ID").apply { top(12) }
        body.addView(directUser)
        body.addView(action("查看公开动态") {
            val id = directUser.text.toString().trim()
            if (id.isBlank()) return@action toast("请填写用户 ID")
            openUser(FanfouUser(id, id, "", false))
        }.apply { top(12) })
    }

    private fun searchUsers(query: String) {
        enterPage { searchUsers(query) }
        shell("用户 · " + query, false)
        val turn = generation
        body.addView(loadingPlaceholders())
        showFloatingLoading("正在搜索用户…")
        runIo(
            { api.searchUsers(query) },
            { users ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                body.removeAllViews()
                if (users.isEmpty()) body.addView(emptyState("没有找到用户", "试试其他关键词。"))
                users.forEach { found ->
                    body.addView(infoRow(found.name, "@" + found.id) { openUser(found) })
                }
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                body.removeAllViews()
                body.addView(emptyState("搜索失败", safeMessage(error)))
            }
        )
    }

    private fun showCompose() {
        enterPage { showCompose() }
        statusLoader = null
        items.clear()
        shell("写动态", false)
        body.setPadding(dp(18), dp(24), dp(18), dp(24))
        body.addView(label("记录此刻的想法", 24, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        body.addView(label("公开发布到饭否", 13, muted).apply { top(8) })
        val editor = EditText(this).apply {
            hint = "写点什么…"
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(muted)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(220)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(140))
            setPadding(0, dp(28), 0, dp(20))
            background = null
        }
        body.addView(editor, LinearLayout.LayoutParams(-1, dp(260)))
        body.addView(divider())
        val footer = row().apply { gravity = Gravity.CENTER_VERTICAL; top(18) }
        body.addView(footer)
        val counter = label("140 / 140", 13, muted).apply { typeface = Typeface.MONOSPACE }
        footer.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        val errorLabel = label("", 13, danger).apply { visibility = View.GONE; top(16) }
        lateinit var publishButton: TextView
        publishButton = action("发布") {
            val text = editor.text.toString().trim()
            if (text.isBlank()) return@action toast("请先写点内容")
            publishButton.isEnabled = false
            publishButton.text = "发布中…"
            errorLabel.visibility = View.GONE
            val turn = generation
            runIo(
                { api.publish(text) },
                {
                    if (turn != generation) return@runIo
                    toast("发布成功")
                    homeItems = emptyList()
                    homeNextId = null
                    homeScrollY = 0
                    showTab("关注")
                },
                { error ->
                    if (turn != generation) return@runIo
                    publishButton.isEnabled = true
                    publishButton.text = "发布"
                    errorLabel.text = "发布失败：" + safeMessage(error)
                    errorLabel.visibility = View.VISIBLE
                }
            )
        }
        publishButton.setTextColor(Color.WHITE)
        publishButton.background = rounded(accent, 12)
        footer.addView(publishButton, LinearLayout.LayoutParams(dp(100), dp(48)))
        body.addView(errorLabel)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counter.text = "${140 - (s?.length ?: 0)} / 140"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun showHistory() {
        enterPage { showHistory() }
        statusLoader = null
        items.clear()
        shell("浏览历史", false)
        renderHistory()
    }

    private fun renderHistory() {
        body.removeAllViews()
        body.addView(sectionHeader("最近看过"))
        body.addView(action("清空历史") {
            session.clearHistory()
            renderHistory()
        }.apply {
            val params = LinearLayout.LayoutParams(-1, -2)
            params.setMargins(dp(18), 0, dp(18), dp(10))
            layoutParams = params
        })
        val entries = session.history()
        if (entries.isEmpty()) body.addView(emptyState("暂无浏览历史", "打开动态或用户主页后会记录在本机。"))
        entries.forEach { entry ->
            body.addView(infoRow(entry.title, if (entry.kind == "user") "用户" else "动态") {
                if (entry.kind == "user") openUser(FanfouUser(entry.id, entry.title, "", false))
                else openStatus(FanfouStatus(
                    entry.id, entry.title, "", FanfouUser("", "", "", false),
                    false, null, null, null
                ))
            })
        }
    }

    private fun showSettings() {
        enterPage { showSettings() }
        statusLoader = null
        items.clear()
        shell("设置", false)
        body.addView(sectionHeader("浏览与内容"))
        val hot = Switch(this).apply {
            text = "显示热门板块"
            textSize = 16f
            isChecked = session.showHot
            setTextColor(ink)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            minHeight = dp(56)
            setOnCheckedChangeListener { _, enabled ->
                session.showHot = enabled
                if (!enabled && activeTab == "热门") activeTab = "关注"
            }
        }
        body.addView(hot)
        body.addView(label("关闭后隐藏入口，并停止热门话题请求。", 13, muted).apply {
            setPadding(dp(18), 0, dp(18), dp(18))
        })
        body.addView(divider())
        body.addView(sectionHeader("本机数据"))
        body.addView(action("清除浏览历史") {
            session.clearHistory()
            toast("已清除")
        }.apply { top(8); horizontalGutter() })
        body.addView(action("清除图片缓存") {
            Glide.get(this).clearMemory()
            io.execute { Glide.get(applicationContext).clearDiskCache() }
            toast("正在清除图片缓存")
        }.apply { top(10); horizontalGutter() })
        body.addView(sectionHeader("账号"))
        body.addView(action("退出登录") {
            AlertDialog.Builder(this)
                .setMessage("退出后会清除授权与本机浏览历史。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ ->
                    session.logout()
                    Glide.get(this).clearMemory()
                    io.execute { Glide.get(applicationContext).clearDiskCache() }
                    showLogin()
                }
                .show()
        }.apply { top(8); horizontalGutter() })
    }

    private fun saveOriginal(status: FanfouStatus) {
        if (status.originalPhoto == null) return toast("这条动态没有原图")
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = status
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 481)
            return
        }
        toast("正在保存原图…")
        runIo(
            { OriginalPhotoSaver(this).save(status) },
            { toast("原图已保存到相册") },
            { error -> toast("保存原图失败：" + safeMessage(error)) }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 481) return
        val toSave = pendingSave
        pendingSave = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && toSave != null) {
            saveOriginal(toSave)
        } else toast("未获得相册写入权限")
    }

    private fun infoRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = box().apply {
            setPadding(dp(18), dp(16), dp(18), 0)
            setBackgroundColor(Color.WHITE)
            minimumHeight = dp(70)
            setOnClickListener { onClick() }
        }
        row.addView(label(title, 16, ink, true))
        if (subtitle.isNotBlank()) row.addView(label(subtitle, 13, muted).apply { top(5) })
        row.addView(divider().apply { top(16) })
        return row
    }

    private fun followRequestRow(user: FanfouUser): View {
        val container = box().apply { setBackgroundColor(Color.WHITE) }
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            minimumHeight = dp(70)
        }
        val info = box().apply {
            setPadding(0, 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            contentDescription = (user.name.ifBlank { user.id }) + " 的主页"
            setOnClickListener { openUser(user) }
        }
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(label(user.name.ifBlank { user.id }, 16, ink, true))
        info.addView(label("@" + user.id, 13, muted).apply { top(5) })

        val deny = requestButton("拒绝", primary = false)
        val accept = requestButton("通过", primary = true)
        val buttons = listOf(deny, accept)
        deny.contentDescription = "拒绝 ${user.name.ifBlank { user.id }} 的关注请求"
        accept.contentDescription = "接受 ${user.name.ifBlank { user.id }} 的关注请求"
        deny.setOnClickListener { respondToFollowRequest(user, false, buttons) }
        accept.setOnClickListener { respondToFollowRequest(user, true, buttons) }
        line.addView(deny, LinearLayout.LayoutParams(dp(76), dp(42)))
        line.addView(accept, LinearLayout.LayoutParams(dp(76), dp(42)).apply {
            leftMargin = dp(10)
        })

        container.addView(line)
        container.addView(divider().apply { top(12) })
        return container
    }

    private fun requestButton(text: String, primary: Boolean): TextView =
        label(text, 14, if (primary) Color.WHITE else ink, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(42)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = if (primary) rounded(accent, 8) else rounded(Color.WHITE, 8, border)
        }

    private fun respondToFollowRequest(
        user: FanfouUser,
        accept: Boolean,
        buttons: List<TextView>
    ) {
        if (!followRequestInFlight.add(user.id)) return
        buttons.forEach { it.isEnabled = false }
        showFloatingLoading(if (accept) "正在接受关注请求…" else "正在拒绝关注请求…")
        val turn = generation
        runIo(
            { api.respondToFollowRequest(user.id, accept) },
            {
                followRequestInFlight.remove(user.id)
                hideFloatingLoading()
                val name = user.name.ifBlank { user.id }
                toast(if (accept) "已接受 $name 的关注请求" else "已拒绝 $name 的关注请求")
                if (turn != generation) return@runIo
                // 重新读取计数与列表，让请求条目、未读数与真实状态保持一致。
                showNotifications()
            },
            { error ->
                followRequestInFlight.remove(user.id)
                hideFloatingLoading()
                buttons.forEach { it.isEnabled = true }
                val verb = if (accept) "接受" else "拒绝"
                toast(verb + "关注请求失败：" + safeMessage(error))
            }
        )
    }

    private fun profileHeader(user: FanfouUser): View = box().apply {
        tag = "profile-header"
        setPadding(dp(18), dp(24), dp(18), dp(18))
        setBackgroundColor(Color.WHITE)
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(avatarFrame(user, 72, true), LinearLayout.LayoutParams(dp(72), dp(72)))
        val names = box().apply { setPadding(dp(14), 0, 0, 0) }
        names.addView(label(user.name.ifBlank { user.id }, 23, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        names.addView(label("@" + user.id, 12, muted).apply { typeface = Typeface.MONOSPACE; top(4) })
        heading.addView(names)
        addView(heading)
        addView(label(user.description.ifBlank { "暂无签名" }, 15, ink).apply {
            top(18)
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        addView(label(
            if (user.protected) "受保护账号 · 仅显示你有权访问的内容" else "公开动态 · 按时间倒序",
            13, muted
        ).apply { top(12) })
        addView(divider().apply { top(18) })
    }

    private fun avatarFrame(user: FanfouUser?, size: Int, large: Boolean = false): FrameLayout =
        FrameLayout(this).apply {
            background = rounded(subtle, size / 2)
            clipToOutline = true
            renderAvatar(this, user, size, large)
        }

    private fun renderAvatar(frame: FrameLayout, user: FanfouUser?, size: Int, large: Boolean = false) {
        frame.removeAllViews()
        frame.addView(label(user?.name?.firstOrNull()?.toString() ?: "饭", size / 3, muted, true).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        val url = (if (large) user?.largeAvatar else user?.avatar).orEmpty()
        if (url.isBlank()) return
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.INVISIBLE
            contentDescription = "${user?.name.orEmpty()}的头像"
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        Glide.with(this).load(url).circleCrop().listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean {
                recordImageFailure("avatar", url, e)
                image.visibility = View.INVISIBLE
                return false
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>,
                dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean
            ): Boolean {
                image.visibility = View.VISIBLE
                return false
            }
        }).into(image)
    }

    private fun recordImageFailure(kind: String, url: String, error: GlideException?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val host = runCatching { java.net.URL(url).host }.getOrDefault("invalid")
        val cause = error?.rootCauses?.firstOrNull()?.javaClass?.simpleName ?: "unknown"
        Log.w("QiafanMedia", "$kind failed on $host: $cause")
    }

    private fun loadingPlaceholders(): View = box().apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val count = (resources.displayMetrics.heightPixels / dp(120) + 1).coerceAtLeast(6)
        repeat(count) {
            val card = box().apply { setPadding(dp(18), dp(22), dp(18), dp(18)) }
            val head = row().apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(View(this@FanfouActivity).apply {
                background = rounded(subtle, 20)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            val identity = box().apply { setPadding(dp(10), 0, 0, 0) }
            identity.addView(View(this@FanfouActivity).apply {
                background = rounded(subtle, 5)
            }, LinearLayout.LayoutParams(dp(108), dp(12)))
            identity.addView(View(this@FanfouActivity).apply {
                background = rounded(subtle, 5)
            }, LinearLayout.LayoutParams(dp(68), dp(9)).apply { topMargin = dp(9) })
            head.addView(identity)
            card.addView(head)
            card.addView(View(this@FanfouActivity).apply {
                background = rounded(subtle, 5)
            }, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(19) })
            card.addView(View(this@FanfouActivity).apply {
                background = rounded(subtle, 5)
            }, LinearLayout.LayoutParams(dp(210), dp(12)).apply { topMargin = dp(10) })
            addView(card)
            addView(divider())
        }
    }

    private fun showFloatingLoading(message: String) {
        val host = loadingHost ?: return
        loadingOverlay?.let { host.removeView(it) }
        val overlay = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = rounded(Color.WHITE, 14, border)
            elevation = dp(8).toFloat()
            isClickable = false
            isFocusable = false
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
            contentDescription = message
        }
        overlay.addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)))
        overlay.addView(label(message, 13, muted).apply {
            setPadding(dp(9), 0, 0, 0)
        })
        host.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(12)
        })
        loadingOverlay = overlay
    }

    private fun hideFloatingLoading() {
        loadingOverlay?.let { loadingHost?.removeView(it) }
        loadingOverlay = null
    }

    private fun relativeTime(value: String): String {
        val parsed = runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(value)?.time
        }.getOrNull() ?: return value
        val minutes = ((System.currentTimeMillis() - parsed) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 1_440 -> "${minutes / 60} 小时前"
            minutes < 10_080 -> "${minutes / 1_440} 天前"
            else -> value
        }
    }

    private fun sectionHeader(title: String): TextView = label(title, 19, ink, true).apply {
        typeface = Typeface.create("serif", Typeface.BOLD)
        setPadding(dp(18), dp(22), dp(18), dp(10))
    }

    private fun emptyState(title: String, detail: String): View = box().apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(66), dp(24), dp(52))
        addView(label(title, 18, ink, true).apply { gravity = Gravity.CENTER })
        addView(label(detail, 13, muted).apply {
            gravity = Gravity.CENTER
            top(8)
        })
    }

    private fun errorRow(message: String, retry: () -> Unit): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rounded(Color.rgb(255, 243, 243), 10, Color.rgb(245, 197, 199))
        val params = LinearLayout.LayoutParams(-1, -2)
        params.setMargins(dp(18), dp(12), dp(18), dp(12))
        layoutParams = params
        addView(label(message, 13, danger), LinearLayout.LayoutParams(0, -2, 1f))
        addView(label("重试", 14, danger, true).apply {
            minWidth = dp(52)
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setOnClickListener { retry() }
        })
    }

    private fun menuEntry(text: String, onClick: () -> Unit): View = box().apply {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        contentDescription = text
        setOnClickListener { onClick() }
        addView(label(text, 16, ink).apply {
            minHeight = dp(54)
            gravity = Gravity.CENTER_VERTICAL
        })
        addView(divider())
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(border)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    private fun View.horizontalGutter() {
        val params = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)
        params.leftMargin = dp(18)
        params.rightMargin = dp(18)
        layoutParams = params
    }

    private fun box(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        setTextColor(ink)
        setHintTextColor(muted)
        minHeight = dp(50)
        setSingleLine(true)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.WHITE, 10, border)
    }

    private fun action(text: String, onClick: () -> Unit): TextView =
        label(text, 15, ink, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(Color.WHITE, 10, border)
            setOnClickListener { onClick() }
        }

    private fun smallAction(text: String, onClick: (TextView) -> Unit): TextView =
        label(text, 12, muted).apply {
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(5), dp(2), dp(5))
            setOnClickListener { onClick(this) }
        }

    private fun icon(text: String, onClick: () -> Unit): TextView =
        label(text, 28, ink).apply {
            gravity = Gravity.CENTER
            contentDescription = "返回上一页"
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun iconButton(kind: String, description: String, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            contentDescription = description
            isFocusable = true
            setOnClickListener { onClick() }
            addView(SymbolIcon(kind, ink), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private inner class SymbolIcon(private val kind: String, color: Int) : View(this@FanfouActivity) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        fun tint(color: Int) {
            pen.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = (width.coerceAtMost(height) / 26f)
            canvas.save()
            canvas.translate((width - 24f * scale) / 2f, (height - 24f * scale) / 2f)
            canvas.scale(scale, scale)
            when (kind) {
                "home" -> {
                    val roof = Path().apply {
                        moveTo(2f, 10f); lineTo(12f, 3f); lineTo(22f, 10f)
                        lineTo(22f, 21f); lineTo(2f, 21f); close()
                    }
                    canvas.drawPath(roof, pen)
                    canvas.drawRect(9f, 13f, 15f, 21f, pen)
                }
                "bell" -> {
                    val bell = Path().apply {
                        moveTo(5f, 18f); cubicTo(7f, 15f, 7f, 12f, 7f, 9f)
                        cubicTo(7f, 2f, 17f, 2f, 17f, 9f)
                        cubicTo(17f, 12f, 17f, 15f, 19f, 18f)
                        close()
                    }
                    canvas.drawPath(bell, pen)
                    canvas.drawArc(9f, 17f, 15f, 23f, 0f, 180f, false, pen)
                }
                "trend" -> {
                    canvas.drawCircle(12f, 12f, 9f, pen)
                    val arrow = Path().apply {
                        moveTo(15.5f, 8.5f); lineTo(13.3f, 13.3f)
                        lineTo(8.5f, 15.5f); lineTo(10.7f, 10.7f); close()
                    }
                    canvas.drawPath(arrow, pen)
                }
                "search" -> {
                    canvas.drawCircle(10.5f, 10.5f, 7f, pen)
                    canvas.drawLine(16f, 16f, 22f, 22f, pen)
                }
                "save" -> {
                    canvas.drawLine(12f, 3f, 12f, 16f, pen)
                    canvas.drawLine(7f, 11f, 12f, 16f, pen)
                    canvas.drawLine(17f, 11f, 12f, 16f, pen)
                    canvas.drawLine(4f, 20f, 20f, 20f, pen)
                }
                "send" -> {
                    val plane = Path().apply {
                        moveTo(21.5f, 2.5f); lineTo(11f, 13f)
                        moveTo(21.5f, 2.5f); lineTo(15f, 21.5f); lineTo(11f, 13f)
                        lineTo(2.5f, 8.5f); close()
                    }
                    canvas.drawPath(plane, pen)
                }
            }
            canvas.restore()
        }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun View.top(value: Int) {
        val params = layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(value)
        layoutParams = params
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun safeMessage(error: Throwable): String =
        error.message?.take(100) ?: "未知错误"

    private fun <T> runIo(
        work: () -> T,
        done: (T) -> Unit,
        failed: (Throwable) -> Unit = { toast(safeMessage(it)) }
    ) {
        io.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(done).onFailure(failed)
            }
        }
    }
}
