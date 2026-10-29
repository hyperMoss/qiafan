package com.example.qiafan.fanfou

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.util.Patterns
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.style.ClickableSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.GestureDetector
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
import com.example.qiafan.BuildConfig
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.text.ParsePosition
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
    private var timelineFooter: View? = null
    private val timelineCards = mutableMapOf<String, View>()
    private var nextId: String? = null
    private var homeItems: List<FanfouStatus> = emptyList()
    private var homeNextId: String? = null
    private var homeScrollY = 0
    private var homeCacheLoaded = false
    private var lastFollowTabTapAt = 0L
    private var busy = false
    private var showingTabs = false
    private var exploreItems: List<FanfouStatus> = emptyList()
    private var exploreNextId: String? = null
    private var exploreScrollY = 0
    private var currentUser: FanfouUser? = null
    private var currentUserLoading = false
    private var navAvatarFrame: FrameLayout? = null
    private var drawerAvatarFrame: FrameLayout? = null
    private val favoriteState = mutableMapOf<String, Boolean>()
    private val favoriteInFlight = mutableSetOf<String>()
    private var generation = 0
    private var pendingSave: FanfouStatus? = null
    private var statusImageDialog: StatusImageDialog? = null
    private var statusActionsSheet: StatusActionsSheet? = null
    private var contentImagePermission: ((Boolean) -> Unit)? = null
    private var notificationSnapshot: NotificationSnapshot? = null
    private var notificationsInFlight = 0
    private var notificationsRefreshedAt = 0L
    private var notificationCacheLoaded = false
    private var notificationError: String? = null
    private var notificationScrollY = 0
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
    private enum class ProfileRelation { SELF, FOLLOWING, OPEN, LOCKED, PENDING, UNKNOWN }
    private val pendingFollowIds = mutableSetOf<String>()
    private var loadedProfileId: String? = null
    private val profileFollowInFlight = mutableSetOf<String>()
    private var chatPeer: FanfouUser? = null
    private var commentTarget: FanfouStatus? = null
    private var detailGeneration = -1
    // 引用卡的原动态缓存：进站后每条回复/转发最多补拉一次，失败不再重复请求。
    private val quoteContext = mutableMapOf<String, FanfouStatus>()
    private val quoteFailed = mutableSetOf<String>()
    private val quoteInFlight = mutableSetOf<String>()
    private var quoteRenderPending = false
    private val pendingQuoteRenders = mutableSetOf<String>()
    private var replyList: List<FanfouStatus> = emptyList()
    private var replyState: String? = null
    private var replyError: String = ""
    private var chatLog: LinearLayout? = null
    private val chatLines = mutableListOf<ChatLine>()
    private var currentPage: (() -> Unit)? = null
    private val backStack = ArrayDeque<() -> Unit>()
    private var restoringPage = false

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
        api = FanfouApi(session) { session.credentials() ?: error("请先授权饭否账号") }
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        if (session.credentials()?.token?.isNotBlank() == true) showTab("关注") else showLogin()
    }

    override fun onDestroy() {
        statusActionsSheet?.dismiss()
        statusImageDialog?.dismiss()
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
        if (showingTabs && activeTab == "通知") notificationScrollY = currentScroll?.scrollY ?: 0
        if (showingTabs && activeTab == "随便看看" && statusLoader != null) {
            exploreItems = items.toList()
            exploreNextId = nextId
            exploreScrollY = currentScroll?.scrollY ?: 0
        }
        if (showingTabs && activeTab == "关注" && statusLoader != null) {
            homeItems = items.toList()
            homeNextId = nextId
            homeScrollY = currentScroll?.scrollY ?: 0
        }
    }

    private fun showLogin() {
        statusActionsSheet?.dismiss()
        statusImageDialog?.dismiss()
        pendingSave = null
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
        notificationsInFlight = 0
        notificationsRefreshedAt = 0L
        notificationCacheLoaded = false
        notificationError = null
        notificationScrollY = 0
        exploreItems = emptyList()
        exploreNextId = null
        exploreScrollY = 0
        homeItems = emptyList()
        homeNextId = null
        homeScrollY = 0
        homeCacheLoaded = false
        quoteContext.clear()
        quoteFailed.clear()
        quoteInFlight.clear()
        quoteRenderPending = false
        pendingQuoteRenders.clear()
        items.clear()
        nextId = null
        favoriteState.clear()
        favoriteInFlight.clear()
        repostInFlight.clear()
        replyInFlight.clear()
        deleteInFlight.clear()
        followRequestInFlight.clear()
        pendingFollowIds.clear()
        loadedProfileId = null
        profileFollowInFlight.clear()
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
        if (tab == "随便看看" && !session.showHot) return
        rememberHomeState()
        backStack.clear()
        currentPage = { showTab(tab) }
        activeTab = tab
        when (tab) {
            "关注" -> showTimeline("关注", true) { api.home(it) }
            "通知" -> showNotifications()
            "随便看看" -> showExplore()
        }
        if (tab != "通知") refreshNotifications()
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
        timelineFooter = null
        timelineCards.clear()
        showingTabs = tabs
        root.removeAllViews()
        val shellHost = FrameLayout(this)
        root.addView(shellHost, FrameLayout.LayoutParams(-1, -1))
        val page = box().apply { setBackgroundColor(Color.WHITE) }
        shellHost.addView(page, FrameLayout.LayoutParams(-1, -1))
        val contentWidth = dp(minOf(720, resources.configuration.screenWidthDp))
        val barWrap = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            elevation = dp(1).toFloat()
        }
        shellHost.addView(barWrap, FrameLayout.LayoutParams(-1, dp(57), Gravity.TOP))
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
        barWrap.addView(divider(), FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM))
        header?.let {
            page.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(57) })
        }
        val content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        loadingHost = content
        loadingOverlay = null
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.WHITE)
            setPadding(0, if (header == null) dp(57) else 0, 0, if (tabs) dp(100) else 0)
            clipToPadding = false
        }
        currentScroll = scroll
        body = box().apply { setPadding(0, 0, 0, dp(24)) }
        scroll.addView(body)
        content.addView(scroll, FrameLayout.LayoutParams(contentWidth, -1, Gravity.CENTER))
        var previousY = 0
        var directionTravel = 0
        var chromeHidden = false
        var navChrome: View? = null
        var composeChrome: View? = null
        fun animateChrome(view: View?, hidden: Boolean, distance: Float) {
            view ?: return
            view.animate().cancel()
            view.animate()
                .translationY(if (hidden) distance else 0f)
                .setDuration(220)
                .start()
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val dy = y - previousY
            val containsMessages = statusLoader != null || (tabs && activeTab == "通知")
            if (containsMessages) {
                directionTravel = when {
                    dy > 0 -> directionTravel.coerceAtLeast(0) + dy
                    dy < 0 -> directionTravel.coerceAtMost(0) + dy
                    else -> directionTravel
                }
                val shouldHide = when {
                    y <= dp(8) -> false
                    directionTravel >= dp(24) -> true
                    directionTravel <= -dp(24) -> false
                    else -> chromeHidden
                }
                if (shouldHide != chromeHidden) {
                    chromeHidden = shouldHide
                    animateChrome(barWrap, shouldHide, -dp(57).toFloat())
                    animateChrome(navChrome, shouldHide, dp(96).toFloat())
                    animateChrome(composeChrome, shouldHide, dp(160).toFloat())
                    directionTravel = 0
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
                        tabs && activeTab == "通知" -> refreshNotifications(force = true)
                        tabs && activeTab == "随便看看" -> showExplore()
                    }
                }
            }
            false
        }
        if (tabs) {
            val nav = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5))
            }
            val navWrap = FrameLayout(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xF7FFFFFF.toInt(), 0xE8EEF5F8.toInt())
                ).apply {
                    cornerRadius = dp(34).toFloat()
                    setStroke(dp(1), 0xEFFFFFFF.toInt())
                }
                elevation = dp(10).toFloat()
            }
            navChrome = navWrap
            val navWidth = dp(minOf(460, (resources.configuration.screenWidthDp - 32).coerceAtLeast(1)))
            shellHost.addView(navWrap, FrameLayout.LayoutParams(navWidth, dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(14)
            })
            navWrap.addView(nav, FrameLayout.LayoutParams(-1, -1))
            listOf("关注", "通知", "随便看看").filter { it != "随便看看" || session.showHot }.forEach { name ->
                val selected = name == activeTab
                val item = box().apply {
                    gravity = Gravity.CENTER
                    if (selected) background = rounded(0x1A0077B5, 26)
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
                val icon = SymbolIcon(symbol, if (selected) accent else muted)
                val iconFrame = FrameLayout(this)
                item.addView(iconFrame, LinearLayout.LayoutParams(dp(54), dp(30)))
                iconFrame.addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                val caption = label(name, 11, if (selected) accent else muted, selected).apply {
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
                background = rounded(accent, 26)
                elevation = dp(7).toFloat()
                setOnClickListener { showCompose() }
            }
            composeChrome = compose
            content.addView(compose, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(((resources.configuration.screenWidthDp - 720).coerceAtLeast(0) / 2) + 18)
                bottomMargin = dp(100)
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
        val restoreExplore = tabs && title == "随便看看" && exploreItems.isNotEmpty()
        if (restoreHome || restoreExplore) {
            items.addAll(if (restoreHome) homeItems else exploreItems)
            nextId = if (restoreHome) homeNextId else exploreNextId
        }
        busy = false
        shell(title, tabs)
        val turn = generation
        when {
            restoreHome || restoreExplore -> {
                renderStatuses(nextId != null)
                val savedY = if (restoreHome) homeScrollY else exploreScrollY
                currentScroll?.post { currentScroll?.scrollTo(0, savedY) }
            }
            tabs && title == "关注" && !homeCacheLoaded -> restoreHomeCache(turn)
            else -> loadStatuses(true)
        }
    }

    /** 进站先用上一会话缓存的关注流首页渲染，再静默合并新动态，避免全屏骨架。 */
    private fun restoreHomeCache(turn: Int) {
        busy = true
        body.addView(loadingPlaceholders())
        runIo(
            { api.cachedHome() },
            { cached ->
                if (turn != generation) return@runIo
                homeCacheLoaded = true
                busy = false
                if (cached == null) {
                    loadStatuses(true)
                    return@runIo
                }
                items.addAll(cached)
                nextId = items.lastOrNull()?.id
                homeItems = items.toList()
                homeNextId = nextId
                renderStatuses(nextId != null)
                loadStatuses(reset = true, mergeFresh = true)
            },
            {
                if (turn == generation) {
                    busy = false
                    loadStatuses(true)
                }
            }
        )
    }

    private fun loadStatuses(reset: Boolean, mergeFresh: Boolean = false) {
        val loader = statusLoader ?: return
        if (busy) return
        busy = true
        val turn = generation
        if (reset) {
            if (items.isEmpty()) {
                body.removeAllViews()
                timelineProfile?.let { body.addView(profileHeader(it)) }
                if (showingTabs && activeTab == "随便看看") {
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
                // 合并进站缓存前先记下视口第一张卡，合并后锚定它，阅读位置不跳。
                val anchor = if (mergeFresh && (currentScroll?.scrollY ?: 0) > 0) firstVisibleItemId() else null
                if (reset && !mergeFresh) {
                    items.clear()
                    nextId = null
                }
                val fresh = if (showingTabs && activeTab == "关注") mergeHome(received) else {
                    val known = items.mapTo(mutableSetOf()) { it.id }
                    received.filter { it.id.isNotBlank() && known.add(it.id) }.also { items.addAll(it) }
                }
                nextId = when {
                    reset && mergeFresh -> if (received.size >= 20) received.lastOrNull()?.id else null
                    received.size >= 20 && (fresh.isNotEmpty() || (showingTabs && activeTab == "关注")) ->
                        nextCursor(received, cursor)?.takeIf { it != cursor }
                    else -> null
                }
                if (showingTabs && activeTab == "关注") {
                    homeCacheLoaded = true
                    homeItems = items.toList()
                    homeNextId = nextId
                }
                if (showingTabs && activeTab == "随便看看") {
                    exploreItems = items.toList()
                    exploreNextId = nextId
                }
                if (reset) renderStatuses(nextId != null, anchor)
                else appendStatuses(fresh, nextId != null)
            },
            { error ->
                if (turn != generation) return@runIo
                busy = false
                hideFloatingLoading()
                val deniedProfile = timelineProfile?.takeIf { (error as? FanfouApiException)?.code == 403 }
                if (deniedProfile != null) {
                    val locked = deniedProfile.copy(protected = true, following = false)
                    timelineProfile = locked
                    statusLoader = null
                    items.clear()
                    nextId = null
                    body.removeAllViews()
                    body.addView(profileHeader(locked))
                    body.addView(emptyState("此用户已开启关注锁", "关注获准后才能查看动态、照片和收藏。"))
                } else {
                    if (items.isEmpty()) {
                        body.removeAllViews()
                        timelineProfile?.let { body.addView(profileHeader(it)) }
                        if (showingTabs && activeTab == "随便看看") addExploreHeader()
                        body.addView(emptyState("暂时无法读取动态", safeMessage(error)))
                    }
                    timelineFooter?.let { body.removeView(it) }
                    timelineFooter = errorRow("加载失败：" + safeMessage(error)) { loadStatuses(reset) }
                        .also { body.addView(it) }
                }
            }
        )
    }

    /**
     * 关注流按 id 从新到旧合并：进站合并与补页都可能拿到夹在已有内容中间的动态，
     * 直接追加会打乱时间序；id 非数值时退回普通追加，尊重服务端顺序。
     */
    private fun mergeHome(received: List<FanfouStatus>): List<FanfouStatus> {
        val known = items.mapTo(mutableSetOf()) { it.id }
        val fresh = received.filter { it.id.isNotBlank() && known.add(it.id) }
        if (fresh.isEmpty()) return fresh
        if ((items + fresh).any { it.id.toLongOrNull() == null }) {
            items.addAll(fresh)
            return fresh
        }
        val merged = (items + fresh).sortedByDescending { it.id.toLong() }
        items.clear()
        items.addAll(merged)
        return fresh
    }

    /** 当前视口顶部第一张卡对应的动态 id，合并新内容后用它锚定阅读位置。 */
    private fun firstVisibleItemId(): String? {
        val scroll = currentScroll ?: return null
        val y = scroll.scrollY
        for (index in 0 until body.childCount) {
            if (body.getChildAt(index).bottom > y) return items.getOrNull(index)?.id
        }
        return items.firstOrNull()?.id
    }

    private fun renderStatuses(hasMore: Boolean, anchorId: String? = null) {
        val scroll = body.parent as? ScrollView
        val previousY = scroll?.scrollY ?: 0
        body.removeAllViews()
        timelineFooter = null
        timelineCards.clear()
        timelineProfile?.let { body.addView(profileHeader(it)) }
        if (showingTabs && activeTab == "随便看看") addExploreHeader()
        if (items.isEmpty()) body.addView(emptyState("这里还没有动态", "下拉刷新，稍后再来看看。"))
        appendStatuses(items, hasMore)
        scroll?.post {
            // 有锚点时把原视口首卡放回原位（上方插入了新动态）；找不到再退回原滚动位置。
            val anchorIndex = anchorId?.let { id -> items.indexOfFirst { it.id == id } }
                ?.takeIf { it >= 0 && it < items.size }
            if (anchorIndex != null && anchorIndex < body.childCount) {
                scroll.scrollTo(0, body.getChildAt(anchorIndex).top - dp(2))
            } else {
                scroll.scrollTo(0, previousY)
            }
        }
    }

    private fun appendStatuses(fresh: List<FanfouStatus>, hasMore: Boolean) {
        timelineFooter?.let { body.removeView(it) }
        timelineFooter = null
        fresh.forEach { status ->
            val index = items.indexOfFirst { it.id == status.id }
            val successor = items.drop(index + 1).firstNotNullOfOrNull { timelineCards[it.id] }
            val position = successor?.let { body.indexOfChild(it) } ?: body.childCount
            val card = statusCard(status)
            timelineCards[status.id] = card
            body.addView(card, position)
        }
        if (hasMore) {
            timelineFooter = label("继续下滑，加载更多", 12, muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(18), dp(16), dp(18))
            }.also { body.addView(it) }
        }
    }

    private fun statusCard(status: FanfouStatus, detail: Boolean = false): View {
        favoriteState.getOrPut(status.id) { status.favorited }
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
        header.addView(label("⋯", 24, muted).apply {
            gravity = Gravity.CENTER
            contentDescription = "更多动态操作"
            isFocusable = true
            setOnClickListener { showStatusActions(status) }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        card.addView(header)
        val content = statusContent(status)
        if (content.text.isNotEmpty()) {
            card.addView(mentionLabel(
                content.text, 16, ink,
                links = content.links,
                emphasis = content.emphasis,
                mentions = content.mentions,
                selectable = detail,
                knownUsers = listOf(status.user) + listOfNotNull(
                    status.replyToUserId.takeIf { it.isNotBlank() }?.let {
                        FanfouUser(it, status.replyToScreenName.ifBlank { it }, "", false)
                    }
                ),
                onBodyClick = if (detail) null else ({ openStatus(status) }),
                onBodyLongPress = if (detail) null else ({ showStatusActions(status) })
            ).apply {
                top(10)
                setLineSpacing(dp(4).toFloat(), 1f)
            })
        }
        // 评论与转发统一：正文 + 引用卡（转发的引用来自内嵌原文或补拉，评论的引用补拉原动态）。
        // 引用确实拿不到时，评论退回一行"来自 @昵称"的来源提示。
        var quoted: FanfouStatus? = null
        var quotedPhoto: String? = null
        if (content.quoteId != null) {
            val quoteId = content.quoteId
            val candidate = quoteContext[quoteId] ?: items.firstOrNull { it.id == quoteId }
            quoted = quotedStatus(content, candidate)
            val embeddedOriginal = embeddedQuotedStatus(content, candidate)
            (quoted ?: embeddedOriginal)?.let { quotedPhoto = it.originalPhoto ?: it.photo }
            val embedded = content.embeddedQuote
            val quoteUnavailable = !quoteFetchAllowed(detail) || quoteId in quoteFailed
            if (quoted == null && embedded != null) {
                card.addView(embeddedQuoteBox(content.captionName, embedded, quoteId, embeddedOriginal),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            } else if (quoted == null && status.repostId == null && quoteUnavailable) {
                card.addView(quoteSourceLine(content.captionName, quoteId),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            if (candidate == null && quoteFetchAllowed(detail)) requestQuoteContext(quoteId)
        }
        quoted?.let { original ->
            card.addView(originalStatusCard(original, listOf(status.user, original.user)),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        (status.originalPhoto ?: status.photo)?.let { url ->
            // 引用卡已带同一张原图时不再重复展示。
            val covered = quotedPhoto == url
            if (covered) return@let
            val targetWidth = dp((minOf(720, resources.configuration.screenWidthDp) - 36).coerceAtLeast(1))
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
            Glide.with(this).load(url).fitCenter().override(targetWidth, dp(340)).listener(object : RequestListener<Drawable> {
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
                    val availableWidth = targetWidth
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
        actions.addView(smallAction("↗ 转发") { showCompose(status) }, LinearLayout.LayoutParams(0, dp(44), 1f))
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
        if (statusActionsSheet != null) return
        statusActionsSheet = StatusActionsSheet(this, status, favoriteState[status.id] ?: status.favorited, ownsStatus(status),
            onClosed = { statusActionsSheet = null }) { action ->
            when (action) {
                StatusQuickAction.REPLY -> openStatus(status)
                StatusQuickAction.FAVORITE -> toggleFavorite(status, null)
                StatusQuickAction.QUICK_REPOST -> repost(status, null)
                StatusQuickAction.REPOST -> showCompose(status)
                StatusQuickAction.DELETE -> confirmDeleteStatus(status)
                StatusQuickAction.VIEW_PHOTO -> showPhoto(status)
                StatusQuickAction.SAVE_PHOTO -> saveOriginal(status)
                StatusQuickAction.CREATE_IMAGE -> showStatusImage(status)
                StatusQuickAction.COPY -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("动态正文", status.text))
                    toast("已复制正文")
                }
            }
        }.also { it.open() }
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
                api.invalidateHomeCache()
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
        val photo = ZoomableImageView(this).apply {
            contentDescription = "${status.user.name}的图片，双击缩放，点按关闭预览"
            setPadding(dp(8), dp(56), dp(8), dp(72))
            setOnClickListener { root.removeView(overlay) }
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
        if (api.repostText(status).length > 140) {
            showCompose(status)
            return
        }
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

    private fun profileRelation(user: FanfouUser): ProfileRelation = when {
        user.id == currentUser?.id -> ProfileRelation.SELF
        user.following == true -> ProfileRelation.FOLLOWING
        user.id in pendingFollowIds -> ProfileRelation.PENDING
        !user.protected -> ProfileRelation.OPEN
        user.following == false -> ProfileRelation.LOCKED
        else -> ProfileRelation.UNKNOWN
    }

    private fun openUser(user: FanfouUser, guessedNickname: String? = null) {
        if (user.id.isBlank()) return
        enterPage { openUser(user, guessedNickname) }
        statusLoader = null
        timelineProfile = user
        loadedProfileId = null
        items.clear()
        nextId = null
        busy = false
        shell(user.name.ifBlank { "@" + user.id }, false)
        body.addView(profileHeader(user))
        body.addView(loadingPlaceholders())
        val turn = generation
        runIo(
            {
                try {
                    api.showUser(user.id)
                } catch (error: FanfouApiException) {
                    if (error.code != 404 || guessedNickname == null) throw error
                    val matches = api.searchUsers(guessedNickname).filter { it.name == guessedNickname }
                        .distinctBy { it.id }
                    if (matches.size != 1) throw error
                    api.showUser(matches.single().id)
                }
            },
            { full ->
                if (turn != generation) return@runIo
                timelineProfile = full
                loadedProfileId = full.id
                session.remember(HistoryEntry("user", full.id, full.name))
                currentPage = { openUser(full) }
                if (full.protected && full.following == null && full.id != currentUser?.id) {
                    body.removeAllViews()
                    body.addView(profileHeader(full))
                    resolveProfileRelation(full, turn)
                } else showProfileContent(full)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                body.removeAllViews()
                body.addView(profileHeader(user))
                body.addView(errorRow("无法读取用户资料：" + safeMessage(error)) {
                    restoringPage = true
                    openUser(user, guessedNickname)
                    restoringPage = false
                })
            }
        )
    }

    private fun resolveProfileRelation(user: FanfouUser, turn: Int) {
        runIo(
            {
                val me = currentUser ?: api.verify()
                user.copy(following = api.isFollowing(me.id, user.id))
            },
            { full ->
                if (turn != generation) return@runIo
                showProfileContent(full)
            },
            { error ->
                if (turn != generation) return@runIo
                hideFloatingLoading()
                body.removeAllViews()
                body.addView(profileHeader(user))
                body.addView(emptyState("此用户已开启关注锁", "关注获准后才能查看动态、照片和收藏。"))
                body.addView(errorRow("无法确认关注关系：" + safeMessage(error)) {
                    showFloatingLoading("正在读取关注关系…")
                    resolveProfileRelation(user, turn)
                })
            }
        )
    }

    private fun showProfileContent(user: FanfouUser) {
        timelineProfile = user
        hideFloatingLoading()
        when (profileRelation(user)) {
            ProfileRelation.LOCKED, ProfileRelation.PENDING, ProfileRelation.UNKNOWN -> {
                statusLoader = null
                items.clear()
                nextId = null
                busy = false
                body.removeAllViews()
                body.addView(profileHeader(user))
                body.addView(emptyState("此用户已开启关注锁", "关注获准后才能查看动态、照片和收藏。"))
            }
            else -> {
                pendingFollowIds.remove(user.id)
                statusLoader = { cursor -> api.userTimeline(user.id, cursor) }
                nextCursor = { received, _ -> received.lastOrNull()?.id }
                loadStatuses(true)
            }
        }
    }

    private fun followProfile(user: FanfouUser) {
        if (loadedProfileId != user.id) return
        val requestSession = session.credentials() ?: return
        if (profileRelation(user) in listOf(ProfileRelation.SELF, ProfileRelation.FOLLOWING, ProfileRelation.PENDING, ProfileRelation.UNKNOWN) ||
            !profileFollowInFlight.add(user.id)) return
        val turn = generation
        val requestApi = FanfouApi { requestSession }
        refreshProfileHeader(user)
        runIo(
            {
                check(session.credentials() == requestSession) { "登录会话已变更" }
                val accepted = when (val result = requestApi.follow(user.id)) {
                    is FanfouFollowResult.Accepted -> result.user
                    FanfouFollowResult.Pending -> user.copy(following = false)
                }
                runCatching {
                    val fresh = requestApi.showUser(user.id)
                    if (fresh.following != null) fresh else {
                        val me = requestApi.verify()
                        fresh.copy(following = requestApi.isFollowing(me.id, user.id))
                    }
                }.getOrElse { accepted.copy(following = null) }
            },
            { full ->
                if (session.credentials() != requestSession) return@runIo
                profileFollowInFlight.remove(user.id)
                val confirmed = full.following == true
                if (confirmed) pendingFollowIds.remove(user.id)
                else if (user.protected) pendingFollowIds.add(user.id)
                if (turn != generation || timelineProfile?.id != user.id) {
                    timelineProfile?.takeIf { it.id == user.id && loadedProfileId == it.id }
                        ?.let(::refreshProfileHeader)
                    return@runIo
                }
                timelineProfile = full
                if (confirmed) {
                    showProfileContent(full)
                    toast("已关注")
                } else {
                    refreshProfileHeader(full)
                    AlertDialog.Builder(this)
                        .setTitle(if (user.protected) "关注申请已发送" else "关注请求已提交")
                        .setMessage(if (user.protected) "已发送关注申请，等待对方通过" else "暂未确认关注状态，请稍后刷新用户资料。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            },
            { error ->
                if (session.credentials() != requestSession) return@runIo
                profileFollowInFlight.remove(user.id)
                if (turn != generation || timelineProfile?.id != user.id) {
                    timelineProfile?.takeIf { it.id == user.id && loadedProfileId == it.id }
                        ?.let(::refreshProfileHeader)
                    return@runIo
                }
                refreshProfileHeader(user)
                toast("关注失败：" + safeMessage(error))
            }
        )
    }

    private fun refreshProfileHeader(user: FanfouUser) {
        if (body.getChildAt(0)?.tag != "profile-header") return
        body.removeViewAt(0)
        body.addView(profileHeader(user), 0)
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
        detailGeneration = generation
        renderStatusDetail(status)
        val turn = generation
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
        val scrollY = currentScroll?.scrollY ?: 0
        body.removeAllViews()
        body.addView(statusCard(status, detail = true))
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
        currentScroll?.post { currentScroll?.scrollTo(0, scrollY) }
    }

    /** 评论的引用原动态确实拿不到时，退回一行来源提示；点按仍可尝试打开原动态。 */
    private fun quoteSourceLine(name: String?, quoteId: String?): View = label(
        if (name != null) "来自 @$name" else "来自原动态", 12, muted
    ).apply {
        minHeight = dp(24)
        gravity = Gravity.CENTER_VERTICAL
        if (quoteId?.isNotBlank() == true) {
            isClickable = true
            isFocusable = true
            contentDescription = "查看来源原动态"
            setOnClickListener { openStatusById(quoteId) }
        }
    }

    /** 原动态暂不可达（已删、受保护或还没拉到）时，引用内容先用转发文本里带的原文。 */
    private fun embeddedQuoteBox(name: String?, text: String, quoteId: String?, original: FanfouStatus? = null): View = box().apply {
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = rounded(subtle, 8)
        val parts = repostQuoteParts(name, text)
        parts.forEachIndexed { index, part ->
            if (index > 0) addView(divider().apply { top(10) })
            if (index == parts.lastIndex && original != null) {
                addView(originalStatusCard(original, listOf(original.user)).apply { top(10) })
                return@forEachIndexed
            }
            val target = when {
                parts.size == 1 -> quoteId?.takeIf { it.isNotBlank() }
                else -> null
            }
            val section = box().apply {
                if (index > 0) top(10)
                if (target != null) {
                    contentDescription = "查看被转发的原动态"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openStatusById(target) }
                }
                addView(label(part.author?.let { "转自 @$it" } ?: "转发的动态", 11, muted, true))
                if (part.text.isNotBlank()) addView(mentionLabel(part.text, 13, ink,
                    onBodyClick = target?.let { id -> { openStatusById(id) } }
                ).apply {
                    top(7)
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
            addView(section)
        }
    }

    /** 只在关注流、通知提及和详情页补拉引用原动态；搜索、随便看看等保持零额外请求。 */
    private fun quoteFetchAllowed(detail: Boolean): Boolean =
        detail || (showingTabs && (activeTab == "关注" || activeTab == "通知"))

    /** 引用卡缺原动态时补拉一次，结果全会话共享；失败不再重复请求。 */
    private fun requestQuoteContext(statusId: String) {
        if (statusId.isBlank() || statusId in quoteContext ||
            statusId in quoteInFlight || statusId in quoteFailed) return
        if (quoteInFlight.size >= 6) return
        quoteInFlight.add(statusId)
        runIo(
            { api.showStatus(statusId) },
            { original ->
                quoteInFlight.remove(statusId)
                quoteContext[statusId] = original
                scheduleQuoteRender(statusId)
            },
            {
                quoteInFlight.remove(statusId)
                quoteFailed.add(statusId)
            }
        )
    }

    private fun scheduleQuoteRender(statusId: String) {
        pendingQuoteRenders.add(statusId)
        if (quoteRenderPending) return
        quoteRenderPending = true
        root.post {
            quoteRenderPending = false
            val resolved = pendingQuoteRenders.toSet()
            pendingQuoteRenders.clear()
            when {
                showingTabs && activeTab == "通知" ->
                    renderNotifications(notificationSnapshot ?: NotificationSnapshot())
                showingTabs && activeTab == "关注" && statusLoader != null -> {
                    items.filter { statusContent(it).quoteId in resolved }.forEach { status ->
                        val previous = timelineCards[status.id] ?: return@forEach
                        val position = body.indexOfChild(previous)
                        if (position < 0) return@forEach
                        val updated = statusCard(status)
                        body.removeViewAt(position)
                        body.addView(updated, position)
                        timelineCards[status.id] = updated
                    }
                }
                !showingTabs && commentTarget != null && statusLoader == null &&
                    generation == detailGeneration -> commentTarget?.let(::renderStatusDetail)
            }
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
            knownUsers = contextUsers,
            links = comment.links,
            emphasis = comment.emphasis,
            mentions = comment.mentions,
            selectable = true
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

    /** 引用卡：紧凑展示被回复或被转发的原动态，点按进入详情；作者行可进其主页。 */
    private fun originalStatusCard(
        original: FanfouStatus,
        contextUsers: List<FanfouUser>
    ): View = box().apply {
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = rounded(subtle, 8)
        isClickable = original.id.isNotBlank()
        isFocusable = original.id.isNotBlank()
        contentDescription = "查看原动态"
        if (original.id.isNotBlank()) setOnClickListener { openStatus(original) }

        val author = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = "打开${original.user.name.ifBlank { original.user.id }}的主页"
            setOnClickListener { openUser(original.user) }
        }
        author.addView(avatarFrame(original.user, 28), LinearLayout.LayoutParams(dp(28), dp(28)))
        author.addView(label(original.user.name.ifBlank { original.user.id }, 13, ink, true).apply {
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        author.addView(label(relativeTime(original.createdAt), 11, muted).apply {
            typeface = Typeface.MONOSPACE
        })
        addView(author)

        addView(mentionLabel(
            original.text.ifBlank { "（无文字）" },
            13,
            ink,
            knownUsers = contextUsers,
            links = original.links,
            emphasis = original.emphasis,
            mentions = original.mentions,
            onBodyClick = original.id.takeIf { it.isNotBlank() }?.let { { openStatus(original) } }
        ).apply {
            top(8)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        original.photo?.let {
            addView(inlinePhoto(original, 170), LinearLayout.LayoutParams(-1, dp(170)).apply {
                topMargin = dp(8)
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
        Glide.with(this@FanfouActivity).load(status.originalPhoto ?: status.photo).fitCenter()
            .override(dp((resources.configuration.screenWidthDp - 36).coerceAtLeast(1)), dp(heightDp))
            .into(image)
    }

    private fun mentionLabel(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false,
        knownUsers: List<FanfouUser> = emptyList(),
        links: List<FanfouTextLink> = emptyList(),
        emphasis: List<FanfouTextEmphasis> = emptyList(),
        mentions: List<FanfouMention> = emptyList(),
        selectable: Boolean = false,
        onBodyClick: (() -> Unit)? = null,
        onBodyLongPress: (() -> Unit)? = null
    ): TextView = label(text, size, color, bold).apply {
        val richText = SpannableString(text)
        emphasis.forEach { range ->
            if (range.start >= 0 && range.end <= text.length && range.start < range.end)
                richText.setSpan(StyleSpan(Typeface.BOLD), range.start, range.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun link(start: Int, end: Int, action: () -> Unit) {
            richText.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = action()
                override fun updateDrawState(drawState: TextPaint) {
                    drawState.color = accent
                    drawState.isUnderlineText = false
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val urls = mutableListOf<IntRange>()
        val mentionRanges = mutableListOf<IntRange>()
        mentions.forEach { mention ->
            if (mention.start >= 0 && mention.end <= text.length && mention.start < mention.end) {
                mentionRanges += mention.start until mention.end
                link(mention.start, mention.end) {
                    val known = (knownUsers + listOfNotNull(currentUser)).firstOrNull { it.id == mention.userId }
                    openUser(known ?: FanfouUser(mention.userId,
                        text.substring(mention.start, mention.end).removePrefix("@"), "", false))
                }
            }
        }
        fun openWeb(url: String) {
            startActivity(Intent(this@FanfouActivity, WebPageActivity::class.java)
                .putExtra(WebPageActivity.EXTRA_URL, url))
        }
        links.forEach { target ->
            if (target.start >= 0 && target.end <= text.length && target.start < target.end &&
                WebPageActivity.isWebUrl(target.url)) {
                urls += target.start until target.end
                link(target.start, target.end) { openWeb(target.url) }
            }
        }
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            if (!Linkify.sUrlMatchFilter.acceptMatch(text, matcher.start(), matcher.end())) continue
            val candidate = matcher.group().orEmpty().trimEnd('.', ',', ';', '!', '?', '。', '，', '；', '！', '？')
            val url = if (candidate.contains("://")) candidate else "https://$candidate"
            if (!WebPageActivity.isWebUrl(url)) continue
            val range = matcher.start() until matcher.start() + candidate.length
            if (urls.any { it.first <= range.last && range.first <= it.last }) continue
            urls += range
            link(range.first, range.last + 1) { openWeb(url) }
        }
        Regex("@([\\p{L}\\p{N}_~-]+)").findAll(text).forEach { match ->
            if ((urls + mentionRanges).any { it.first <= match.range.last && match.range.first <= it.last }) return@forEach
            val userId = match.groupValues[1]
            link(match.range.first, match.range.last + 1) {
                val known = (knownUsers + listOfNotNull(currentUser)).firstOrNull {
                    it.id.equals(userId, ignoreCase = true) || it.name == userId
                }
                if (known != null) openUser(known)
                else openUser(FanfouUser(userId, userId, "", false), guessedNickname = userId)
            }
        }
        this.text = richText
        if (selectable) {
            setTextIsSelectable(true)
            highlightColor = 0x553A6855
        } else {
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        fun spanAt(event: MotionEvent): ClickableSpan? {
            val textLayout = layout ?: return null
            val x = event.x - totalPaddingLeft + scrollX
            val y = event.y - totalPaddingTop + scrollY
            if (y < 0 || y >= textLayout.height) return null
            val line = textLayout.getLineForVertical(y.toInt())
            if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) return null
            val offset = textLayout.getOffsetForHorizontal(line, x)
            return richText.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull {
                offset >= richText.getSpanStart(it) && offset < richText.getSpanEnd(it)
            }
        }
        val detector = GestureDetector(this@FanfouActivity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (selectable && selectionStart != selectionEnd) return false
                val span = spanAt(event)
                if (span != null) span.onClick(this@apply) else if (!selectable) performClick()
                return !selectable
            }
            override fun onLongPress(event: MotionEvent) {
                if (onBodyLongPress != null) performLongClick()
            }
        })
        if (onBodyClick != null) setOnClickListener { onBodyClick() }
        if (onBodyLongPress != null) setOnLongClickListener { onBodyLongPress(); true }
        setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            !selectable
        }
    }

    private fun openStatusById(id: String) {
        if (id.isBlank()) return
        val cached = quoteContext[id] ?: items.firstOrNull { it.id == id }
        if (cached != null) {
            openStatus(cached)
            return
        }
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
        val prefixName = api.replyName(target)
        setBackgroundColor(Color.WHITE)
        addView(divider())
        val bar = row().apply {
            gravity = Gravity.BOTTOM
            setPadding(dp(18), dp(10), dp(18), dp(14))
        }
        addView(bar)
        // 正文补上 `@对方昵称 `；前缀长度计入输入额度，回复关系参数仍使用 ID。
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
                "无法回复：原作者昵称过长"
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
        busy = false
        shell("通知", true)
        renderNotifications(notificationSnapshot ?: NotificationSnapshot(counts = unreadCounts ?: FanfouCounts(0, 0, 0)))
        currentScroll?.post { currentScroll?.scrollTo(0, notificationScrollY) }
        refreshNotifications()
    }

    private fun refreshNotifications(force: Boolean = false) {
        if (notificationsInFlight > 0) return
        if (!force && notificationCacheLoaded && SystemClock.elapsedRealtime() - notificationsRefreshedAt < 60_000L) return
        val owner = session.credentials() ?: return
        val requestApi = FanfouApi(session) { owner }
        notificationsInFlight = 1
        fun stillSignedIn() = session.credentials() == owner
        fun redraw() {
            if (showingTabs && activeTab == "通知") renderNotifications(notificationSnapshot ?: NotificationSnapshot())
        }
        fun fetch() {
            notificationsInFlight = 4
            notificationError = null
            fun finished() {
                notificationsInFlight--
                if (notificationsInFlight == 0) notificationsRefreshedAt = SystemClock.elapsedRealtime()
            }
            fun <T> request(work: () -> T, merge: (NotificationSnapshot, Result<T>) -> NotificationSnapshot) {
                runIo({ runCatching(work) }, { result ->
                    if (!stillSignedIn()) return@runIo
                    finished()
                    notificationSnapshot = merge(notificationSnapshot ?: NotificationSnapshot(), result)
                    if (result.isFailure) notificationError = "部分通知更新失败，已保留上次内容。下拉可重试。"
                    redraw()
                })
            }
            request({ requestApi.counts() }) { old, result ->
                result.getOrNull()?.let {
                    unreadCounts = it
                    updateUnreadBadge()
                    old.copy(counts = it)
                } ?: old
            }
            request({ requestApi.mentions() }) { old, result ->
                old.copy(mentions = result.takeIf { it.isSuccess } ?: old.mentions ?: result)
            }
            request({ requestApi.conversations() }) { old, result ->
                old.copy(conversations = result.takeIf { it.isSuccess } ?: old.conversations ?: result)
            }
            request({ requestApi.followRequests() }) { old, result ->
                old.copy(requests = result.takeIf { it.isSuccess } ?: old.requests ?: result)
            }
        }
        if (!notificationCacheLoaded) {
            runIo({ requestApi.cachedNotifications() }, { cached ->
                if (!stillSignedIn()) return@runIo
                notificationCacheLoaded = true
                notificationSnapshot = cached
                unreadCounts = cached.counts
                updateUnreadBadge()
                redraw()
                fetch()
            }, {
                if (!stillSignedIn()) return@runIo
                notificationCacheLoaded = true
                fetch()
            })
        } else fetch()
    }

    private fun renderNotifications(snapshot: NotificationSnapshot) {
        val scrollY = currentScroll?.scrollY ?: 0
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
                    currentScroll?.post { currentScroll?.scrollTo(0, 0) }
                }
            }
            tabs.addView(item, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        when (notificationSection) {
            "提及" -> snapshot.mentions?.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无提及与回复", "有新消息时会出现在这里。"))
                list.forEach { body.addView(statusCard(it)) }
            }?.onFailure { body.addView(errorRow("提及加载失败：" + safeMessage(it)) { refreshNotifications(true) }) }
            "私信" -> snapshot.conversations?.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无私信", "收到私信后会出现在这里。"))
                list.forEach { conversation ->
                    body.addView(dmRow(conversation))
                }
            }?.onFailure { body.addView(errorRow("私信加载失败：" + safeMessage(it)) { refreshNotifications(true) }) }
            else -> snapshot.requests?.onSuccess { list ->
                if (list.isEmpty()) body.addView(emptyState("暂无关注请求", "新的关注请求会出现在这里。"))
                list.forEach { user ->
                    body.addView(followRequestRow(user))
                }
            }?.onFailure { body.addView(errorRow("关注请求加载失败：" + safeMessage(it)) { refreshNotifications(true) }) }
        }
        val pending = when (notificationSection) {
            "提及" -> snapshot.mentions == null
            "私信" -> snapshot.conversations == null
            else -> snapshot.requests == null
        }
        if (pending) body.addView(label("正在同步通知…", 14, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(40), dp(18), dp(40))
        })
        notificationError?.let { reason ->
            body.addView(label(reason, 12, muted).apply { setPadding(dp(18), dp(16), dp(18), dp(16)) })
        }
        currentScroll?.post { currentScroll?.scrollTo(0, scrollY) }
    }

    private fun showExplore() {
        if (!session.showHot) return
        showTimeline("随便看看", true) { api.publicTimeline(it) }
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
                    renderStatuses(nextId != null)
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
        body.addView(divider().apply { top(12) })
    }

    private fun searchPublic(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isBlank()) return toast("请输入搜索词")
        session.rememberSearch(query)
        showTimeline("搜索 · " + query) { api.publicSearch(query, it) }
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
        val updated = snapshot.conversations?.getOrNull()?.map {
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
        val text = value.trim()
        val position = ParsePosition(0)
        val format = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).apply { isLenient = false }
        format.parse(text, position)?.time?.takeIf { position.index == text.length }
    }.getOrNull()

    private fun localDateTime(timestamp: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameYear = then.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
        val pattern = if (sameYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return SimpleDateFormat(pattern, Locale.CHINA).format(timestamp)
    }

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
        val parsed = parseFanfouTime(value) ?: return "时间未知"
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsed)
        return when (chatDay(value)) {
            "今天" -> clock
            "昨天" -> "昨天 $clock"
            else -> localDateTime(parsed)
        }
    }

    private fun drawer() {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            elevation = dp(24).toFloat()
        }
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
        listOf("个人主页", "搜索", "浏览历史", "收藏", "设置").forEach { name ->
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

    private fun showCompose(repostTarget: FanfouStatus? = null) {
        enterPage { showCompose(repostTarget) }
        statusLoader = null
        items.clear()
        shell(if (repostTarget == null) "写动态" else "转发动态", false)
        body.setPadding(dp(18), dp(24), dp(18), dp(24))
        body.addView(label(if (repostTarget == null) "记录此刻的想法" else "转发并说点什么", 24, ink, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD)
        })
        body.addView(label(if (repostTarget == null) "公开发布到饭否" else "可在原文前添加自己的话，转发正文最多 140 字", 13, muted).apply { top(8) })
        val editor = EditText(this).apply {
            hint = "写点什么…"
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(muted)
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(220)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (repostTarget == null) filters = arrayOf(InputFilter.LengthFilter(140))
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
        val submitLabel = if (repostTarget == null) "发布" else "转发"
        lateinit var publishButton: TextView
        publishButton = action(submitLabel) {
            val text = editor.text.toString().trim()
            if (text.isBlank()) return@action toast("请先写点内容")
            if (text.length > 140) return@action toast("正文超过 140 字，请编辑后发送")
            publishButton.isEnabled = false
            editor.isEnabled = false
            publishButton.text = "${submitLabel}中…"
            errorLabel.visibility = View.GONE
            val turn = generation
            runIo(
                { if (repostTarget == null) api.publish(text) else api.repost(repostTarget, text) },
                {
                    if (turn != generation) return@runIo
                    toast("${submitLabel}成功")
                    homeItems = emptyList()
                    homeNextId = null
                    homeScrollY = 0
                    showTab("关注")
                },
                { error ->
                    if (turn != generation) return@runIo
                    publishButton.isEnabled = true
                    editor.isEnabled = true
                    publishButton.text = submitLabel
                    errorLabel.text = "${submitLabel}失败：" + safeMessage(error)
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
                counter.setTextColor(if ((s?.length ?: 0) > 140) danger else muted)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        repostTarget?.let {
            editor.setText(api.repostText(it))
            editor.setSelection(0)
        }
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
            text = "显示随便看看"
            textSize = 16f
            isChecked = session.showHot
            setTextColor(ink)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            minHeight = dp(56)
            setOnCheckedChangeListener { _, enabled ->
                session.showHot = enabled
                if (!enabled && activeTab == "随便看看") activeTab = "关注"
            }
        }
        body.addView(hot)
        body.addView(label("关闭后隐藏入口，并停止公开动态请求。", 13, muted).apply {
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
            io.execute {
                Glide.get(applicationContext).clearDiskCache()
                StatusImageSharer(applicationContext).clearCache()
            }
            toast("正在清除图片缓存")
        }.apply { top(10); horizontalGutter() })
        body.addView(action("清除通知与信息流缓存") {
            session.clearNotificationCache()
            notificationSnapshot = null
            notificationCacheLoaded = false
            notificationScrollY = 0
            homeCacheLoaded = false
            homeItems = emptyList()
            homeNextId = null
            homeScrollY = 0
            quoteContext.clear()
            quoteFailed.clear()
            toast("已清除通知与信息流缓存")
        }.apply { top(10); horizontalGutter() })
        body.addView(divider().apply { top(18) })
        body.addView(sectionHeader("应用"))
        body.addView(infoRow("关于恰饭", "版本 " + BuildConfig.VERSION_NAME) { showAbout() })
        body.addView(sectionHeader("账号"))
        body.addView(action("退出登录") {
            AlertDialog.Builder(this)
                .setMessage("退出后会清除授权与本机浏览历史。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出") { _, _ ->
                    session.logout()
                    Glide.get(this).clearMemory()
                    io.execute {
                        Glide.get(applicationContext).clearDiskCache()
                        StatusImageSharer(applicationContext).clearCache()
                    }
                    showLogin()
                }
                .show()
        }.apply { top(8); horizontalGutter() })
    }

    private fun showAbout() {
        enterPage { showAbout() }
        statusLoader = null
        timelineProfile = null
        items.clear()
        busy = false
        shell("关于恰饭", false)
        body.addView(box().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
            addView(label("恰", 32, accent, true).apply {
                gravity = Gravity.CENTER
                background = rounded(subtle, 20)
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(label("恰饭", 26, ink, true).apply {
                typeface = Typeface.create("serif", Typeface.BOLD)
                top(18)
            })
            addView(label("版本 " + BuildConfig.VERSION_NAME, 14, muted).apply { top(8) })
        })
        body.addView(divider())
        val projectUrl = "https://github.com/hyperMoss/qiafan"
        body.addView(infoRow("开源项目 ↗", projectUrl) {
            startActivity(Intent(this, WebPageActivity::class.java)
                .putExtra(WebPageActivity.EXTRA_URL, projectUrl))
        }.apply {
            isFocusable = true
            contentDescription = "开源项目，打开 GitHub 仓库，" + projectUrl
        })
    }

    private fun showStatusImage(status: FanfouStatus) {
        if (statusImageDialog != null) return
        val knownQuotes = quoteContext.toMap()
        statusImageDialog = StatusImageDialog(
            this, status,
            loadReplies = { api.replies(status.id) },
            loadOriginal = { id -> knownQuotes[id] ?: api.showStatus(id) },
            requestSavePermission = { callback ->
                if (Build.VERSION.SDK_INT < 29 &&
                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    contentImagePermission = callback
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 482)
                } else callback(true)
            },
            onClosed = {
                statusImageDialog = null
                contentImagePermission = null
            }
        ).also { it.open() }
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
        if (requestCode == 482) {
            val callback = contentImagePermission
            contentImagePermission = null
            callback?.invoke(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            return
        }
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
            if (loadedProfileId != user.id) "正在读取用户资料…"
            else if (user.protected) "受保护账号 · 仅显示你有权访问的内容" else "公开动态 · 按时间倒序",
            13, muted
        ).apply { top(12) })
        val relation = profileRelation(user)
        if (relation != ProfileRelation.SELF) {
            val inFlight = user.id in profileFollowInFlight
            val metadataReady = loadedProfileId == user.id
            val controls = row().apply { top(16) }
            addView(controls)
            controls.addView(action(when {
                !metadataReady -> "读取资料中…"
                inFlight -> "正在提交…"
                relation == ProfileRelation.FOLLOWING -> "已关注"
                relation == ProfileRelation.PENDING -> "已申请"
                relation == ProfileRelation.UNKNOWN -> "确认关系中…"
                user.protected -> "申请关注"
                else -> "关注"
            }) { followProfile(user) }.apply {
                isEnabled = metadataReady && !inFlight && relation !in listOf(ProfileRelation.FOLLOWING, ProfileRelation.PENDING, ProfileRelation.UNKNOWN)
                alpha = if (isEnabled) 1f else 0.6f
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val canChat = metadataReady && !user.protected
            controls.addView(action("私聊") { openConversation(user) }.apply {
                isEnabled = canChat
                setTextColor(if (canChat) Color.WHITE else muted)
                background = rounded(if (canChat) accent else subtle, 12)
                contentDescription = if (canChat) "私聊，打开会话" else "私聊不可用，" + if (metadataReady) "对方已加锁" else "正在读取资料"
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(12) })
        }
        if (relation != ProfileRelation.SELF) {
            val links = row().apply { top(12) }
            addView(links)
            listOf("关注", "被关注", "TA看到的").forEach { title ->
                links.addView(label(title, 14, accent, true).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(48)
                    background = rounded(subtle, 10)
                    isFocusable = true
                    isEnabled = loadedProfileId == user.id
                    alpha = if (isEnabled) 1f else 0.5f
                    contentDescription = when (title) {
                        "关注" -> "关注，查看他关注的人"
                        "被关注" -> "被关注，查看关注他的人"
                        else -> "TA看到的，打开用户主页时间线"
                    }
                    setOnClickListener {
                        when (title) {
                            "关注" -> showConnections(user, false)
                            "被关注" -> showConnections(user, true)
                            else -> showTimeline(user.name + " · TA看到的") { api.viewedTimeline(user.id, it) }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (title != "关注") leftMargin = dp(8)
                })
            }
        }
        addView(divider().apply { top(18) })
    }

    private fun showConnections(user: FanfouUser, followers: Boolean) {
        val users = mutableListOf<FanfouUser>()
        var page = 1
        var hasMore = true
        var loading = false
        var failure: String? = null
        lateinit var open: () -> Unit
        lateinit var load: () -> Unit
        fun render() {
            val scrollY = currentScroll?.scrollY ?: 0
            body.removeAllViews()
            users.forEach { person -> body.addView(connectionRow(person)) }
            if (users.isEmpty() && !loading && failure == null) body.addView(emptyState("暂无用户", "这里还没有可显示的用户。"))
            if (loading) body.addView(label("正在读取…", 14, muted).apply { setPadding(dp(18), dp(24), dp(18), dp(24)) })
            else if (failure != null) body.addView(errorRow(failure!!) { load() })
            else if (hasMore) body.addView(action("加载更多") { load() }.apply { horizontalGutter() })
            currentScroll?.post { currentScroll?.scrollTo(0, scrollY) }
        }
        load = load@{
            if (loading) return@load
            loading = true
            failure = null
            render()
            runIo({ if (followers) api.followers(user.id, page) else api.followingUsers(user.id, page) }, { received ->
                loading = false
                val known = users.mapTo(mutableSetOf()) { it.id }
                val fresh = received.filter { it.id.isNotBlank() && known.add(it.id) }
                users.addAll(fresh)
                hasMore = received.size >= 20 && fresh.isNotEmpty()
                page++
                if (currentPage === open) render()
            }, { error ->
                loading = false
                failure = "无法读取用户列表：" + safeMessage(error)
                if (currentPage === open) render()
            })
        }
        open = {
            enterPage(open)
            statusLoader = null
            timelineProfile = null
            items.clear()
            busy = false
            shell(user.name + if (followers) " · 被关注" else " · 关注", false)
            render()
            if (users.isEmpty() && failure == null) load()
        }
        open()
    }

    private fun connectionRow(user: FanfouUser): View = box().apply {
        setBackgroundColor(Color.WHITE)
        isFocusable = true
        contentDescription = "打开${user.name.ifBlank { user.id }}的主页"
        setOnClickListener { openUser(user) }
        val line = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        line.addView(avatarFrame(user, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        line.addView(box().apply {
            setPadding(dp(12), 0, 0, 0)
            addView(label(user.name.ifBlank { user.id }, 16, ink, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(label("@" + user.id, 13, muted).apply {
                top(4)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(line)
        addView(divider())
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

    private fun showFloatingLoading(message: String, showMessage: Boolean = true) {
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
        if (showMessage) overlay.addView(label(message, 13, muted).apply {
            setPadding(dp(9), 0, 0, 0)
        })
        host.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (currentScroll?.paddingTop ?: 0) + dp(12)
        })
        loadingOverlay = overlay
    }

    private fun hideFloatingLoading() {
        loadingOverlay?.let { loadingHost?.removeView(it) }
        loadingOverlay = null
    }

    private fun relativeTime(value: String): String {
        val parsed = parseFanfouTime(value) ?: return "时间未知"
        val minutes = ((System.currentTimeMillis() - parsed) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 1_440 -> "${minutes / 60} 小时前"
            minutes < 10_080 -> "${minutes / 1_440} 天前"
            else -> localDateTime(parsed)
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
