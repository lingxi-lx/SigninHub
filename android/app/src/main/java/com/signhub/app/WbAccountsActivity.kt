package com.signhub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * WorkBuddy 多账号管理（视觉对齐主界面）：
 * - 沉浸式头图（蓝紫渐变 + 网格线）+ 圆角卡片列表
 * - 入场动画（卡片自底向上依次浮现）、按压反馈、签到按钮进行中旋转
 * - 圆角渐变主按钮「添加账号」「一键全部签到」、状态徽章、顶部毛玻璃质感
 */
class WbAccountsActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var progress: ProgressBar
    private var polling = false
    private var destroyed = false

    private val dp: Float get() = resources.displayMetrics.density

    // 主界面同款配色
    private val cBg = Color.parseColor("#0a0b10")
    private val cCard = Color.parseColor("#171B26")
    private val cCardHi = Color.parseColor("#1B1F2C")
    private val cLine = Color.parseColor("#232A3A")
    private val cTxt = Color.parseColor("#EEF1F8")
    private val cMuted = Color.parseColor("#8B93A7")
    private val cBrand = Color.parseColor("#6C8CFF")
    private val cBrand2 = Color.parseColor("#9B6CFF")
    private val cOk = Color.parseColor("#37E0A6")
    private val cDanger = Color.parseColor("#FF6B81")
    private val cWarn = Color.parseColor("#FFCF5C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式：内容顶进状态栏
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        // 头图永远是深色渐变，状态栏图标固定用白色，否则浅色模式深色图标会在深底上看不见
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        // ── 头图（主界面 hero 同款：蓝紫渐变 + KICKER + 大标题） ──
        val hero = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2A2F55"), Color.parseColor("#12141F")))
                .apply { cornerRadii = floatArrayOf(0f,0f,0f,0f, (28*dp),(28*dp),(28*dp),(28*dp)) }
        }
        val heroInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20*dp).toInt(), (34*dp).toInt(), (20*dp).toInt(), (26*dp).toInt()) }
        heroInner.addView(TextView(this).apply {
            text = "WORKBUDDY ACCOUNTS"; setTextColor(Color.parseColor("#AAB6E8")); textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.22f
        })
        heroInner.addView(TextView(this).apply {
            text = "多账号管理"; setTextColor(Color.WHITE); textSize = 34f
            setTypeface(typeface, Typeface.BOLD); setPadding(0, (6*dp).toInt(), 0, (6*dp).toInt())
        })
        heroInner.addView(TextView(this).apply {
            text = "OAuth 登录 · 每账号独立签到 · 后台遍历"; setTextColor(Color.parseColor("#C9CFE8")); textSize = 12.5f
        })
        hero.addView(heroInner)
        root.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 主按钮区 ──
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((18*dp).toInt(), (16*dp).toInt(), (18*dp).toInt(), 0) }
        actions.addView(pillButton("＋ 添加账号（网页登录）", listOf(cBrand, cBrand2)) { addAccount() })
        actions.addView(pillButton("⚡ 一键全部签到", listOf(Color.parseColor("#22B98A"), Color.parseColor("#37E0A6")), topMarginDp = 10) { checkAll() })

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4*dp).toInt()).apply { topMargin = (12*dp).toInt() }
            isIndeterminate = true
            visibility = View.GONE
        }
        actions.addView(progress)
        statusBar = TextView(this).apply {
            text = ""; setTextColor(cMuted); textSize = 12f; setPadding(0, (8*dp).toInt(), 0, 0)
        }
        actions.addView(statusBar)
        root.addView(actions)

        // ── 卡片列表 ──
        val scroll = ScrollView(this).apply { clipToPadding = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((18*dp).toInt(), (14*dp).toInt(), (18*dp).toInt(), (24*dp).toInt())
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        // 沉浸式细节：不在 decorView 上加 padding（那会把头图顶到状态栏下面、顶部露出一条黑边）。
        // 只把安全区高度补给头图文字与列表底部，渐变背景本身铺到屏幕最顶端。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            heroInner.setPadding((20 * dp).toInt(), (26 * dp).toInt() + top, (20 * dp).toInt(), (22 * dp).toInt())
            listBox.setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (24 * dp).toInt() + bottom)
            insets
        }

        refreshList()
    }

    /**
     * 卡片内的胶囊按钮（自绘 TextView）。
     *
     * 为什么不用 `android.widget.Button`：即使把 background 换成 GradientDrawable，
     * 系统仍会叠加 `minWidth=64dp` / `minHeight=48dp` / `stateListAnimator`（点击抬升阴影）
     * 与默认 inset —— 视觉上就是「圆角图里套了个灰方块」（用户 m20782 的截图）。
     */
    private fun pill(
        text: String,
        colors: List<Int>,
        textColor: Int,
        weight: Float = 0f,
        widthDp: Int = 0,
        endMarginDp: Int = 0,
        stroke: Int = 0,
        onClick: (View) -> Unit,
    ): View = TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 13.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors.toIntArray()).apply {
            cornerRadius = (12 * dp)
            if (stroke != 0) setStroke((1 * dp).toInt(), stroke)
        }
        layoutParams = LinearLayout.LayoutParams(
            if (weight > 0f) 0 else (widthDp * dp).toInt(),
            (42 * dp).toInt(), weight,
        ).apply { rightMargin = (endMarginDp * dp).toInt() }
        setOnClickListener { v ->
            v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }.start()
            onClick(v)
        }
    }

    /** 圆角渐变胶囊主按钮（带按压缩放动画）。高度固定 50dp，两个主按钮必须一样高。 */
    private fun pillButton(text: String, colors: List<Int>, topMarginDp: Int = 0, onClick: () -> Unit): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50*dp).toInt())
                .apply { topMargin = (topMarginDp * dp).toInt() }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors.toIntArray())
                .apply { cornerRadius = (15*dp) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onClick()
            }
            TextView(this@WbAccountsActivity).apply {
                this.text = text; setTextColor(Color.WHITE); textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.also { addView(it) }
        }

    /** 圆角卡片，入场动画用 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((15*dp).toInt(), (14*dp).toInt(), (15*dp).toInt(), (14*dp).toInt())
        background = GradientDrawable().apply { cornerRadius = (18*dp); setColor(cCard); setStroke((1*dp).toInt(), cLine) }
    }

    private fun chip(text: String, color: Int, bg: Int): TextView = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = 10.5f; setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = (20*dp); setColor(bg); setStroke((1*dp).toInt(), color) }
        setPadding((9*dp).toInt(), (3*dp).toInt(), (9*dp).toInt(), (3*dp).toInt())
    }

    private fun refreshList() {
        val accounts = WbAccountStore.all(this)
        listBox.removeAllViews()
        if (accounts.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有账号\n\n点上方「＋ 添加账号」用网页登录添加（支持多个）。登录成功后自动保存，可单签或一键全部签到；每日定时也会在后台遍历所有账号自动签到。"
                setTextColor(cMuted); textSize = 13f
                setLineSpacing(0f, 1.5f)
                gravity = Gravity.CENTER; setPadding((8*dp).toInt(), (40*dp).toInt(), (8*dp).toInt(), 0)
            }
            listBox.addView(empty)
            empty.alpha = 0f; empty.animate().alpha(1f).setDuration(400).start()
            return
        }

        // 汇总条：N 个账号 · M 个已签
        val doneCnt = accounts.count { it.lastStatus == "success" || it.lastStatus == "already" }
        listBox.addView(TextView(this).apply {
            text = "共 ${accounts.size} 个账号 · 今日已签 $doneCnt"
            setTextColor(cMuted); textSize = 11.5f
            setPadding((4*dp).toInt(), 0, 0, (10*dp).toInt())
        })

        accounts.forEachIndexed { idx, acc ->
            val c = card()
            val status = acc.lastStatus
            val ok = status == "success" || status == "already"

            // 卡片边框颜色随状态（成功淡绿 / 失败淡红 / 默认灰）
            if (ok) (c.background as GradientDrawable).setStroke((1*dp).toInt(), Color.parseColor("#37E0A660"))
            if (status == "fail") (c.background as GradientDrawable).setStroke((1*dp).toInt(), Color.parseColor("#FF6B8155"))

            // 第一行：头像圈 + 名字/标签 + 状态徽章
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val avatar = TextView(this).apply {
                text = acc.displayName().take(1).uppercase()
                setTextColor(Color.WHITE); textSize = 15f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#5B8CFF"), Color.parseColor("#9B6CFF")))
                    .apply { cornerRadius = (13*dp) }
                layoutParams = LinearLayout.LayoutParams((40*dp).toInt(), (40*dp).toInt())
            }
            row.addView(avatar)
            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((11*dp).toInt(), 0, 0, 0) }
            nameCol.addView(TextView(this).apply {
                text = acc.displayName() + (if (acc.variant == "ai") "（国际版）" else "")
                setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            })
            nameCol.addView(TextView(this).apply {
                text = when {
                    status == "success" -> "今日已签 · " + acc.lastMessage.replace("签到成功 · ", "")
                    status == "already" -> acc.lastMessage
                    status == "fail" -> "失败：" + acc.lastMessage.removePrefix("失败：")
                    else -> "今日尚未执行"
                }
                setTextColor(if (status == "fail") cDanger else cMuted); textSize = 11.5f
                setPadding(0, (3*dp).toInt(), 0, 0); maxLines = 2
            })
            row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(when (status) {
                "success" -> chip("✓ 已签", cOk, Color.parseColor("#37E0A622"))
                "already" -> chip("✓ 已签", cWarn, Color.parseColor("#FFCF5C22"))
                "fail" -> chip("✕ 失败", cDanger, Color.parseColor("#FF6B8122"))
                else -> chip("待签", cMuted, Color.parseColor("#8B93A722"))
            })
            c.addView(row)

            // 元信息：uid · domain · 最近签到时间
            val meta = listOf(acc.uid.take(8), acc.domain).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) c.addView(TextView(this).apply {
                text = meta; setTextColor(Color.parseColor("#5F677A")); textSize = 10.5f
                setPadding(0, (8*dp).toInt(), 0, 0); maxLines = 1
            })

            // 按钮行：签到（渐变胶囊）+ 删除（描边幽灵）
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12*dp).toInt(), 0, 0) }
            // 用户 m20782：原生 Button 即使换了背景，系统仍叠加 minWidth/stateListAnimator，
            // 看着还是「圆角图里套灰方块」。改成 TextView 自绘。
            val signBtn = pill(text = if (ok) "再签一次" else "签到",
                colors = listOf(Color.parseColor("#2BD9A0"), Color.parseColor("#22B98A")),
                textColor = Color.WHITE, weight = 1f, widthDp = 0, endMarginDp = 10) {
                it.isEnabled = false
                it.animate().alpha(0.6f).setDuration(120).start()
                checkOne(acc)
            }
            btns.addView(signBtn)
            val renameBtn = pill(text = "改名",
                colors = listOf(Color.TRANSPARENT, Color.TRANSPARENT),
                textColor = Color.parseColor("#C7D2FF"),
                widthDp = 74, endMarginDp = 10,
                stroke = Color.parseColor("#6C8CFF55")) {
                rename(acc)
            }
            btns.addView(renameBtn)
            val delBtn = pill(text = "删除",
                colors = listOf(Color.TRANSPARENT, Color.TRANSPARENT),
                textColor = Color.parseColor("#FFB3BF"),
                widthDp = 86, endMarginDp = 0,
                stroke = Color.parseColor("#FF6B8155")) {
                confirmDelete(acc)
            }
            btns.addView(delBtn)
            c.addView(btns)

            // 入场动画：自底向上、依次错开浮现
            listBox.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12*dp).toInt()
            })
            c.alpha = 0f
            c.translationY = (36 * dp)
            c.animate().alpha(1f).translationY(0f).setDuration(340)
                .setStartDelay((idx * 70).toLong())
                .setInterpolator(OvershootInterpolator(0.9f))
                .start()
        }
    }

    // ---------- 添加账号（站内网页登录） ----------
    /**
     * 与另外三家统一：不再跳外部浏览器，直接在 App 内开 LoginWebActivity 的站内 WebView。
     * 实测 codebuddy.cn 的登录页在移动 UA 下能正常渲染「手机号 / 微信 / SSO / QQ / 邮箱」，
     * 授权结果由服务端按 state 记账，容器是浏览器还是 WebView 不影响结果。
     * 传 EXTRA_TO_LIST=true 让它把结果追加到多账号列表，而不是覆盖设置页里的主账号。
     */
    private fun addAccount() {
        if (polling) { toast("正在登录中，请稍候"); return }
        // 区域必须由用户选：国际版与国内版是两套账号体系，登错域名拿到的凭据
        // 打国际版接口会一直 401（反之亦然），而且两个站的登录态互相不可见。
        SignDialog.list(
            this,
            kicker = "ADD ACCOUNT",
            title = "选择 WorkBuddy 区域",
            subtitle = "两个站是独立的账号体系，凭据不通用。",
            items = listOf(
                SignDialog.Item("国内版", "codebuddy.cn · 每日 100 积分"),
                SignDialog.Item("国际版", "workbuddy.ai · 目前没有签到活动，只读余额"),
            ),
        ) { which ->
            val p = if (which == 1) LoginWebActivity.P_WB_AI else LoginWebActivity.P_WB
            try {
                startActivity(
                    Intent(this, LoginWebActivity::class.java)
                        .putExtra(LoginWebActivity.EXTRA_PROVIDER, p)
                        .putExtra(LoginWebActivity.EXTRA_TO_LIST, true)
                )
            } catch (e: Exception) {
                toast("打开登录页失败：${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从站内登录页返回时刷新列表（登录成功后 LoginWebActivity 会 finish）
        if (!polling) refreshList()
    }

    private fun startPolling(state: String, variant: String) {
        polling = true
        Thread {
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (!destroyed && System.currentTimeMillis() < deadline) {
                val tb = try { WbApi.oauthPoll(variant, state) } catch (e: Exception) { null }
                if (tb != null) {
                    val prof = try { WbApi.fetchProfile(variant, state, tb.accessToken, tb.domain) } catch (e: Exception) { null }
                    val acc = WbAccount(
                        id = System.currentTimeMillis().toString() + (1000..9999).random(),
                        uid = prof?.optString("uid", "") ?: "",
                        nickname = prof?.optString("nickname", "") ?: "",
                        email = prof?.optString("email", "") ?: "",
                        // optString 的默认值参数要求非空 String：原来写成
                        // `prof?.optString("enterpriseId", prof?.optString(...))`，
                        // 第二个参数是 String? → 编译期警告 + 运行时可能落 null。
                        enterpriseId = (prof?.optString("enterpriseId", "") ?: "")
                            .ifBlank { prof?.optString("enterprise_id", "") ?: "" },
                        // 手机号打码串（用户 m16555）：WB 的 login/account 返回里
                        // 如果有 phone / mobile / phoneNumber 就顺手带上；
                        // 没有就留空，displayName() 会回落到昵称 → 邮箱 → uid 前 8 位。
                        phone = (prof?.optString("phone", "") ?: "")
                            .ifBlank { prof?.optString("mobile", "") ?: "" }
                            .ifBlank { prof?.optString("phoneNumber", "") ?: "" },
                        accessToken = tb.accessToken, refreshToken = tb.refreshToken, domain = tb.domain,
                        variant = variant, expiresAt = tb.expiresAt, createdAt = System.currentTimeMillis(),
                    )
                    WbAccountStore.upsert(this, acc)
                    runOnUiThread {
                        polling = false
                        progress.visibility = View.GONE
                        statusBar.text = "已添加：${acc.displayName()}"
                        toast("账号已添加")
                        refreshList()
                    }
                    return@Thread
                }
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
            polling = false
            runOnUiThread {
                progress.visibility = View.GONE
                if (statusBar.text.contains("授权")) statusBar.text = "登录超时（10 分钟）或已取消，可重试"
            }
        }.start()
    }

    // ---------- 签到 ----------

    /**
     * 把一次 WorkBuddy 多账号签到结果写进历史库（records 表）。
     *
     * 与 MultiAccountsActivity.saveRecord 同一个坑：多账号页原来只更新 WbAccountStore 的
     * lastStatus/lastMessage，从不写 HistoryStore —— 首页卡片 / 连续天数 / 热力图 / 统计
     * 全读 records 表，所以那边签完这边还是「今日尚未执行」。
     * account_key 用账号自己的 id，与 LocalApi.checkinAll 里多账号 job 的写法一致。
     */
    private fun saveRecord(a: WbAccount) {
        if (a.lastStatus.isBlank()) return
        try {
            val day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
            HistoryStore(this).upsert(
                day,
                CheckinResult(
                    "workbuddy", a.lastStatus, "${a.displayName()}：${a.lastMessage}",
                    "", a.totalPoints, a.streakDays
                ),
                a.id
            )
        } catch (_: Exception) {
            // 落库失败不该让签到流程报错；账号卡上的 lastStatus 仍然有效
        }
    }

    private fun checkOne(acc: WbAccount) {
        statusBar.text = "签到中：${acc.displayName()}…"
        progress.visibility = View.VISIBLE
        Thread {
            // 闸门：与首页一键签到 / 后台定时任务撞车时，同一账号只打一次上游。
            // CheckinGate 只认 CheckinResult，这里把 WbAccount 从闭包里带出来。
            var fresh = acc
            val r = try {
                CheckinGate.run("workbuddy", acc.id) {
                    val a = WbApi.checkin(acc)
                    fresh = a
                    CheckinResult(
                        "workbuddy",
                        if (a.lastStatus.isBlank()) "fail" else a.lastStatus,
                        "${a.displayName()}：${a.lastMessage}",
                        "", a.totalPoints, a.streakDays
                    )
                }
            } catch (e: Exception) {
                // 裸 Thread 里抛异常没人接 → 直接杀进程。这里兜成一条 fail 结果。
                CheckinResult("workbuddy", "fail",
                    "${acc.displayName()}：${e.message ?: e.javaClass.simpleName}", "")
            }
            val a = if (r.status == fresh.lastStatus) fresh else fresh.copy(
                lastStatus = r.status, lastMessage = r.message)
            try { WbAccountStore.update(this, a); saveRecord(a) } catch (_: Exception) {}
            runOnUiThread {
                // 页面可能已经关了：跑完还去碰 progress/statusBar 会抛
                // 「View 已 attach 到已销毁的窗口」
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                statusBar.text = "${a.displayName()}：${a.lastMessage}"
                toast("${a.displayName()}：${a.lastMessage}")
                refreshList()
            }
        }.start()
    }

    private fun checkAll() {
        val accounts = WbAccountStore.all(this)
        if (accounts.isEmpty()) { toast("还没有账号"); return }
        statusBar.text = "全部签到中（${accounts.size} 个账号）…"
        progress.visibility = View.VISIBLE
        Thread {
            var ok = 0
            accounts.forEach { acc ->
                // 单个账号异常不能中断整批（以前没 try，一个账号炸掉线程就崩）
                try {
                    var fresh = acc
                    val r = CheckinGate.run("workbuddy", acc.id) {
                        val x = WbApi.checkin(acc)
                        fresh = x
                        CheckinResult(
                            "workbuddy",
                            if (x.lastStatus.isBlank()) "fail" else x.lastStatus,
                            "${x.displayName()}：${x.lastMessage}",
                            "", x.totalPoints, x.streakDays
                        )
                    }
                    val a = if (r.status == fresh.lastStatus) fresh else fresh.copy(
                        lastStatus = r.status, lastMessage = r.message)
                    WbAccountStore.update(this, a)
                    saveRecord(a)
                    if (a.lastStatus == "success" || a.lastStatus == "already") ok++
                } catch (e: Exception) {
                    android.util.Log.e("WbAccountsActivity", "账号签到异常", e)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                statusBar.text = "全部完成：$ok/${accounts.size} 成功"
                toast("全部完成：$ok/${accounts.size}")
                refreshList()
            }
        }.start()
    }

    /**
     * 改显示名（用户 m02619）。
     *
     * 只写 `alias`，不动 `nickname`（那是 OAuth `login/account` 回来的上游昵称，
     * 每次登录/刷新都会覆盖）。留空保存 = 恢复自动名。
     * 走 `WbAccountStore.update()`（按 id 整条替换），只改 alias 不碰凭据。
     */
    private fun rename(acc: WbAccount) {
        val auto = acc.copy(alias = "").displayName() +
            (if (acc.variant == "ai") "（国际版）" else "")
        SignDialog.input(
            this,
            kicker = "RENAME",
            title = "自定义名称",
            subtitle = "起一个自己好认的名字，比如「主号」「国际号」。\n留空保存则恢复自动名称（当前显示：$auto）。",
            fields = listOf(SignDialog.Field("名称", initial = acc.alias)),
            okText = "保存",
        ) { v ->
            val alias = v[0].trim().take(24)
            WbAccountStore.update(this, acc.copy(alias = alias))
            refreshList()
            toast(if (alias.isBlank()) "已恢复自动名称" else "已改名为「$alias」")
        }
    }

    private fun confirmDelete(acc: WbAccount) {
        SignDialog.confirm(
            this,
            kicker = "REMOVE",
            title = "删除账号",
            message = "确定删除「${acc.displayName()}」？\n删除后需要重新网页登录才能加回来。",
            okText = "删除",
            danger = true,
        ) {
            WbAccountStore.remove(this, acc.id)
            refreshList()
            toast("已删除")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() { destroyed = true; super.onDestroy() }
}
