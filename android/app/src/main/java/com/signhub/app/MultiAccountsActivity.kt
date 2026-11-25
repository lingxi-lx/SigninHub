package com.signhub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Trae Solo / Qoder CN / MiniMax Code 的多账号管理页。
 *
 * WorkBuddy 早就有一条独立的多账号通道（WbAccountsActivity + WbAccountStore）。
 * 三家原来只有设置页里那一组扁平字段，等于只能一个账号。这个页面把
 * 「增删账号 / 一键全部签到」的能力补齐，视觉与 WbAccountsActivity 完全一致，
 * 只把「添加账号」的动作从 OAuth 换成各自已验证的登录通道：
 *
 *   Trae     -> LoginWebActivity(P_TRAE)：打开 trae.cn 读 X-Cloudide-Session
 *   Qoder CN -> LoginWebActivity(P_QD)  ：device flow 轮询拿 dt-/drt-
 *   MiniMax  -> LoginWebActivity(P_MM)  ：读 localStorage['_token']
 *
 * 三家的凭据都从 LoginWebActivity 的「多账号通道」回来（EXTRA_TO_LIST = true），
 * 由 LoginWebActivity 直接追加到对应的 *AccountStore，本页只负责刷新列表。
 */
class MultiAccountsActivity : Activity() {

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val P_TRAE = "traesolo"
        const val P_QD = "qoder"
        const val P_MM = "minimax"
        const val P_HW = "huawei"
    }

    private lateinit var provider: String
    private lateinit var listBox: LinearLayout
    private lateinit var statusBar: TextView
    private lateinit var progress: ProgressBar
    private var destroyed = false

    private val dp: Float get() = resources.displayMetrics.density

    private val cBg = Color.parseColor("#0a0b10")
    private val cCard = Color.parseColor("#171B26")
    private val cLine = Color.parseColor("#232A3A")
    private val cTxt = Color.parseColor("#EEF1F8")
    private val cMuted = Color.parseColor("#8B93A7")
    private val cBrand = Color.parseColor("#6C8CFF")
    private val cBrand2 = Color.parseColor("#9B6CFF")
    private val cOk = Color.parseColor("#37E0A6")
    private val cDanger = Color.parseColor("#FF6B81")
    private val cWarn = Color.parseColor("#FFCF5C")

    /** 各家的标题与文案 */
    private val kicker: String get() = when (provider) {
        P_TRAE -> "TRAE SOLO ACCOUNTS"
        P_QD -> "QODER CN ACCOUNTS"
        P_HW -> "HUAWEI CODEARTS ACCOUNTS"
        else -> "MINIMAX CODE ACCOUNTS"
    }
    private val subtitle: String get() = when (provider) {
        P_TRAE -> "网页登录 · 每账号独立签到 · 后台遍历"
        P_QD -> "device flow 登录 · drt- 自动续期 · 后台遍历"
        P_HW -> "站内登录 · 每账号一份 Cookie · 后台遍历"
        else -> "网页登录 · 每账号独立签到 · 后台遍历"
    }
    private val loginProvider: String get() = when (provider) {
        P_TRAE -> LoginWebActivity.P_TRAE
        P_QD -> LoginWebActivity.P_QD
        P_HW -> LoginWebActivity.P_HW
        else -> LoginWebActivity.P_MM
    }
    private val emptyHint: String get() = when (provider) {
        P_TRAE -> "还没有账号\n\n点上方「＋ 添加账号」用网页登录添加（支持多个）。\n登录后自动保存 X-Cloudide-Session（约 14 天，可自动续期）；每日定时会在后台遍历所有账号签到。"
        P_QD -> "还没有账号\n\n点上方「＋ 添加账号」用网页登录添加（支持多个）。\n登录后自动保存 drt- 开头的 refreshToken（有效期一年，可反复续期），只填它也能长期签到。"
        P_HW -> "还没有账号\n\n点上方「＋ 添加账号」用网页登录添加（支持多个）。\n登录后自动保存整条会话 Cookie（含 devclouddevuibjJ_SESSION_ID）；\n华为的会话 Cookie 静置 30~60 分钟会过期，过期后重新添加一次即可。"
        else -> "还没有账号\n\n点上方「＋ 添加账号」用网页登录添加（支持多个）。\n登录后自动保存 mmoat_ 开头的 accessToken；每日定时会在后台遍历所有账号签到。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 未知 provider 一律落回 Trae，绝不静默落进 MiniMax 分支。
        // 历史 bug（用户 m17284）：WorkBuddy 的「多账号管理」把 "workbuddy" 传了进来，
        // 本页只认 traesolo / qoder / minimax，else 分支是 MiniMax —— 于是 WorkBuddy
        // 的账号页显示成 MiniMax，点「添加账号」打开的也是 MiniMax 的登录网址。
        // 调用方已在 app.js 改成走 openAccounts()，这里再加一道兜底。
        val rawProvider = intent.getStringExtra(EXTRA_PROVIDER) ?: P_TRAE
        provider = when (rawProvider) {
            P_TRAE, P_QD, P_MM, P_HW -> rawProvider
            else -> P_TRAE
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        val hero = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2A2F55"), Color.parseColor("#12141F")))
                .apply { cornerRadii = floatArrayOf(0f,0f,0f,0f,(28*dp),(28*dp),(28*dp),(28*dp)) }
        }
        val heroInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20*dp).toInt(), (34*dp).toInt(), (20*dp).toInt(), (26*dp).toInt())
        }
        heroInner.addView(TextView(this).apply {
            text = kicker; setTextColor(Color.parseColor("#AAB6E8")); textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.22f
        })
        heroInner.addView(TextView(this).apply {
            text = "多账号管理"; setTextColor(Color.WHITE); textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (6*dp).toInt(), 0, (6*dp).toInt())
        })
        heroInner.addView(TextView(this).apply {
            text = subtitle; setTextColor(Color.parseColor("#C9CFE8")); textSize = 12.5f
        })
        hero.addView(heroInner)
        root.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18*dp).toInt(), (16*dp).toInt(), (18*dp).toInt(), 0)
        }
        actions.addView(pillButton("＋ 添加账号（网页登录）", listOf(cBrand, cBrand2)) { addAccount() })
        actions.addView(pillButton("⚡ 一键全部签到", listOf(Color.parseColor("#22B98A"), Color.parseColor("#37E0A6")), 10) { checkAll() })
        actions.addView(pillButton("✎ 手动填凭据", listOf(Color.parseColor("#2C3A55"), Color.parseColor("#3B4E70")), 10) { manualAdd() })

        progress = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4*dp).toInt())
                .apply { topMargin = (12*dp).toInt() }
            isIndeterminate = true
            visibility = View.GONE
        }
        actions.addView(progress)
        statusBar = TextView(this).apply {
            text = ""; setTextColor(cMuted); textSize = 12f; setPadding(0, (8*dp).toInt(), 0, 0)
        }
        actions.addView(statusBar)
        root.addView(actions)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18*dp).toInt(), (14*dp).toInt(), (18*dp).toInt(), (24*dp).toInt())
        }
        scroll.addView(listBox)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top
            val bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
            heroInner.setPadding((20*dp).toInt(), (26*dp).toInt() + top, (20*dp).toInt(), (22*dp).toInt())
            listBox.setPadding((18*dp).toInt(), (14*dp).toInt(), (18*dp).toInt(), (24*dp).toInt() + bottom)
            insets
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // 从登录页回来时刷新：LoginWebActivity 已经把新账号写进列表了
        if (::listBox.isInitialized) refreshList()
    }

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
            TextView(this@MultiAccountsActivity).apply {
                this.text = text; setTextColor(Color.WHITE); textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.also { addView(it) }
        }

    /**
     * 卡片内的胶囊按钮（自绘 TextView）。
     *
     * 为什么不用 `android.widget.Button`：即使把 background 换成 GradientDrawable，
     * 系统仍会叠加 `minWidth=64dp` / `minHeight=48dp` / `stateListAnimator`（点击时
     * 抬升阴影）与默认 inset —— 视觉上就是「圆角图里套了个灰方块」（用户 m20782 的截图）。
     * TextView + 自绘背景才完全可控。
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

    private fun card(): LinearLayout = LinearLayout(this).apply {        orientation = LinearLayout.VERTICAL
        setPadding((15*dp).toInt(), (14*dp).toInt(), (15*dp).toInt(), (14*dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = (18*dp); setColor(cCard); setStroke((1*dp).toInt(), cLine)
        }
    }

    private fun chip(text: String, color: Int, bg: Int): TextView = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = 10.5f
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = (20*dp); setColor(bg); setStroke((1*dp).toInt(), color)
        }
        setPadding((9*dp).toInt(), (3*dp).toInt(), (9*dp).toInt(), (3*dp).toInt())
    }

    // ---------- 列表 ----------

    /** 统一成「一条记录」的视图模型，三家用同一套卡片渲染 */
    private data class Row(
        val id: String,
        val name: String,
        val meta: String,
        val status: String,
        val message: String,
        val total: Int,
        val used: Int,
    )

    private fun rows(): List<Row> = when (provider) {
        P_TRAE -> TraeAccountStore.all(this).map {
            Row(it.id, it.displayName(), it.metaLine(), it.lastStatus, it.lastMessage, it.totalPoints, it.usedPoints)
        }
        P_QD -> QoderAccountStore.all(this).map {
            Row(it.id, it.displayName(), it.metaLine(), it.lastStatus, it.lastMessage, it.totalPoints, it.usedPoints)
        }
        P_HW -> HwAccountStore.all(this).map {
            Row(it.id, it.displayName(), it.metaLine(), it.lastStatus, it.lastMessage, it.totalPoints, it.usedPoints)
        }
        else -> MmAccountStore.all(this).map {
            Row(it.id, it.displayName(), it.metaLine(), it.lastStatus, it.lastMessage, it.totalPoints, it.usedPoints)
        }
    }

    private fun refreshList() {
        val list = rows()
        listBox.removeAllViews()
        if (list.isEmpty()) {
            val empty = TextView(this).apply {
                text = emptyHint
                setTextColor(cMuted); textSize = 13f
                setLineSpacing(0f, 1.5f)
                gravity = Gravity.CENTER
                setPadding((8*dp).toInt(), (40*dp).toInt(), (8*dp).toInt(), 0)
            }
            listBox.addView(empty)
            empty.alpha = 0f; empty.animate().alpha(1f).setDuration(400).start()
            return
        }

        val doneCnt = list.count { it.status == "success" || it.status == "already" }
        listBox.addView(TextView(this).apply {
            text = "共 ${list.size} 个账号 · 今日已签 $doneCnt"
            setTextColor(cMuted); textSize = 11.5f
            setPadding((4*dp).toInt(), 0, 0, (10*dp).toInt())
        })

        list.forEachIndexed { idx, acc ->
            val c = card()
            val ok = acc.status == "success" || acc.status == "already"
            if (ok) (c.background as GradientDrawable).setStroke((1*dp).toInt(), Color.parseColor("#37E0A660"))
            if (acc.status == "fail") (c.background as GradientDrawable).setStroke((1*dp).toInt(), Color.parseColor("#FF6B8155"))

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = acc.name.take(1).uppercase()
                setTextColor(Color.WHITE); textSize = 15f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#5B8CFF"), Color.parseColor("#9B6CFF")))
                    .apply { cornerRadius = (13*dp) }
                layoutParams = LinearLayout.LayoutParams((40*dp).toInt(), (40*dp).toInt())
            })

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding((11*dp).toInt(), 0, 0, 0)
            }
            nameCol.addView(TextView(this).apply {
                text = acc.name
                setTextColor(cTxt); textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            })
            val subline = buildString {
                append(when (acc.status) {
                    "success" -> "今日已签 · " + acc.message.replace("签到成功 · ", "")
                    "already" -> acc.message
                    "fail" -> "失败：" + acc.message.removePrefix("失败：")
                    else -> "今日尚未执行"
                })
                if (acc.total >= 0) append(" · 可用 ${acc.total}")
            }
            nameCol.addView(TextView(this).apply {
                text = subline
                setTextColor(if (acc.status == "fail") cDanger else cMuted); textSize = 11.5f
                setPadding(0, (3*dp).toInt(), 0, 0); maxLines = 2
            })
            row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(when (acc.status) {
                "success" -> chip("✓ 已签", cOk, Color.parseColor("#37E0A622"))
                "already" -> chip("✓ 已签", cWarn, Color.parseColor("#FFCF5C22"))
                "fail" -> chip("✕ 失败", cDanger, Color.parseColor("#FF6B8122"))
                else -> chip("待签", cMuted, Color.parseColor("#8B93A722"))
            })
            c.addView(row)

            if (acc.meta.isNotBlank()) c.addView(TextView(this).apply {
                text = acc.meta; setTextColor(Color.parseColor("#5F677A")); textSize = 10.5f
                setPadding(0, (8*dp).toInt(), 0, 0); maxLines = 1
            })

            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, (12*dp).toInt(), 0, 0)
            }
            // 用户 m20782：原来用 android.widget.Button，即使换了背景，
            // 系统仍会叠加 minWidth/minHeight、stateListAnimator 的阴影与内边距，
            // 视觉上还是「灰方块套了个圆角图」。这里改成 TextView 自绘，行为与主界面一致。
            btns.addView(pill(text = if (ok) "再签一次" else "签到",
                colors = listOf(Color.parseColor("#2BD9A0"), Color.parseColor("#22B98A")),
                textColor = Color.WHITE, weight = 1f, widthDp = 0, endMarginDp = 10) {
                it.isEnabled = false
                it.animate().alpha(0.6f).setDuration(120).start()
                checkOne(acc.id, acc.name)
            })
            btns.addView(pill(text = "改名",
                colors = listOf(Color.TRANSPARENT, Color.TRANSPARENT),
                textColor = Color.parseColor("#C7D2FF"),
                widthDp = 74, endMarginDp = 10,
                stroke = Color.parseColor("#6C8CFF55")) {
                rename(acc.id, acc.name)
            })
            btns.addView(pill(text = "删除",
                colors = listOf(Color.TRANSPARENT, Color.TRANSPARENT),
                textColor = Color.parseColor("#FFB3BF"),
                widthDp = 86, endMarginDp = 0,
                stroke = Color.parseColor("#FF6B8155")) {
                confirmDelete(acc.id, acc.name)
            })
            c.addView(btns)

            listBox.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (12*dp).toInt() })
            c.alpha = 0f
            c.translationY = (36 * dp)
            c.animate().alpha(1f).translationY(0f).setDuration(340)
                .setStartDelay((idx * 70).toLong())
                .setInterpolator(OvershootInterpolator(0.9f))
                .start()
        }
    }

    // ---------- 添加 ----------

    private fun addAccount() {
        startActivity(Intent(this, LoginWebActivity::class.java).apply {
            putExtra(LoginWebActivity.EXTRA_PROVIDER, loginProvider)
            putExtra(LoginWebActivity.EXTRA_TO_LIST, true)
        })
    }

    /** 手动填凭据（网页登录抓不到时的兜底），直接写进多账号列表 */
    private fun manualAdd() {
        val hint = when (provider) {
            P_TRAE -> "填 X-Cloudide-Session（约 14 天，能自动续期）或 Cloud-IDE-Token（约 8 小时）。\n" +
                "设备号留空会自动按凭据派生一个稳定号；填 16 位数字可指定 x-device-id。"
            P_QD -> "推荐填 drt- 开头的 refreshToken（有效期一年，只填它也能签到）；\n" +
                "也可以填 dt- 开头的 accessToken（30 天过期）。"
            P_HW -> "填整串会话 Cookie（必须含 devclouddevuibjJ_SESSION_ID，\n" +
                "通常还有 agencyID 与 devclouddevuibjtcftk）。\n" +
                "浏览器 F12 → Application → Cookies 里整行复制即可。\n" +
                "注意：华为的会话 Cookie 静置 30~60 分钟会过期。"
            else -> "填 mmoat_ 开头的 accessToken（约 60 个字符）。"
        }
        val f1 = when (provider) {
            P_TRAE -> "X-Cloudide-Session 或 Cloud-IDE-Token"
            P_QD -> "drt- 开头的 refreshToken（推荐）"
            P_HW -> "整串会话 Cookie"
            else -> "mmoat_ 开头的 accessToken"
        }
        val f2 = when (provider) {
            P_TRAE -> "设备号 x-device-id（可选，16 位数字）"
            P_QD -> "dt- 开头的 accessToken（可选）"
            P_HW -> "备注（可选，留空则显示 Cookie 短码）"
            else -> "user_id（可选）"
        }
        val f3 = when (provider) {
            P_MM -> "device_id（可选）"
            P_HW -> "（留空即可）"
            else -> "备注（可选）"
        }

        // MiniMax 的第三栏被 device_id 占掉了，别名只能另开一栏（用户 m02619）
        val fields = buildList {
            add(SignDialog.Field(f1, multiline = true))
            add(SignDialog.Field(f2))
            add(SignDialog.Field(f3))
            if (provider == P_MM) add(SignDialog.Field("备注（可选，随时可在卡片上改）"))
        }

        SignDialog.input(
            this,
            kicker = "MANUAL",
            title = "手动添加账号",
            subtitle = hint,
            fields = fields,
            validate = { v -> if (v[0].length < 10) "凭据太短，至少 10 个字符" else null },
        ) { v ->
            val a = v[0]
            val b = v[1]
            val c = v[2]
            val alias = when (provider) {
                // 华为的第二栏本来就是「备注」，第三栏是占位；沿用老位置别让用户改习惯
                P_HW -> b.trim().take(24)
                // MiniMax 的第三栏是 device_id，别名在第四栏
                P_MM -> v.getOrElse(3) { "" }.trim().take(24)
                else -> c.trim().take(24)
            }
            when (provider) {
                P_TRAE -> {
                    val isJwt = a.startsWith("eyJ") && a.count { it == '.' } == 2
                    // 设备号：用户填了就用他的（真实机器号最可靠）；没填就按这条凭据
                    // **确定性派生**一个 —— 同一账号永远算出同一串，不会每次变。
                    //
                    // 9074 的真实语义是「这个设备号在该账号下没有注册记录」，上游文案
                    // 「当前参与用户太多」是误导。所以两条硬规则：① 一个账号一个号，
                    // 绝不共用（共用 = 用别人的注册记录）；② 永久稳定（每次重生成 =
                    // 每天换新设备 = 永远未注册）。v2.4 随机生成、v2.5 统一成主账号那串
                    // 都因此失败。
                    val dev = if (b.matches(Regex("\\d{6,20}"))) b else DeviceId.derive(a)
                    TraeAccountStore.upsert(this, TraeAccount(
                        id = AccountIds.stable(a, if (isJwt) "" else a),
                        token = if (isJwt) a else "",
                        session = if (isJwt) "" else a,
                        deviceId = dev,
                        // 第三栏是「备注」，原来被丢掉了（用户 m02619 要求可自定义名称）
                        alias = alias,
                    ))
                }
                P_QD -> {
                    val tok = if (a.startsWith("dt-")) a else ""
                    val rt = if (a.startsWith("drt-")) a else if (b.startsWith("drt-")) b else ""
                    val at = if (a.startsWith("drt-")) b else tok
                    QoderAccountStore.upsert(this, QoderAccount(
                        id = AccountIds.stable(at, rt),
                        token = at, refresh = rt, uid = "", machineId = "", region = "cn",
                        // 第三栏是「备注」，原来被丢掉了（用户 m02619）
                        alias = alias,
                    ))
                }
                P_HW -> {
                    HwAccountStore.upsert(this, HwAccount(
                        // 身份键优先 agencyID（BUGS-v2.46 P1），与 LoginWebActivity 同一口径
                        id = AccountIds.stable(HwAccount.identityOf(a)),
                        cookie = a,
                        // 华为原来就把备注写 nickname，这里两者都填，保证老逻辑与 alias 一致
                        nickname = alias,
                        alias = alias,
                    ))
                    // 手动粘贴这条路没有 probe 环节，只能靠 agencyID 这个「真登录才发」的
                    // 附属 cookie 下结论（BUGS-v2.46 P2）。没有它就不写结论 ——
                    // 留空表示「判断不了」，isReady 按可用处理，不冤枉用户。
                    if (a.contains("agencyID=")) HwVerified.set(this, a, true)
                }
                else -> MmAccountStore.upsert(this, MmAccount(
                    id = AccountIds.stable(a, if (b.matches(Regex("\\d+"))) b else ""),
                    token = a, userId = if (b.matches(Regex("\\d+"))) b else "",
                    deviceId = if (c.isNotBlank()) c else "",
                    alias = alias,
                ))
            }
            toast("已添加")
            refreshList()
        }
    }

    // ---------- 签到 ----------

    /**
     * 当天日期（与 LocalApi.today() 同口径，Asia/Shanghai）。
     * 多账号页与 LocalApi 必须用同一个 day 才能落到同一批记录上，
     * 否则首页/统计按 LocalApi 的 day 查、这里按另一个 day 写，永远对不上。
     */
    private fun today(): String =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * 把一次签到结果写进历史库（records 表），account_key 用账号自己的 id。
     *
     * 这是 H1 的修法：原先这里只更新 `*AccountStore` 的 lastStatus/lastMessage，
     * 从没写过 `HistoryStore` —— 于是多账号页显示「已签到」，回首页卡片还是
     * 「今日尚未执行」，连续天数 / 热力图 / 统计也都不动（两条签到链路各写各的存储）。
     * 主账号（设置页那组凭据）在 LocalApi 里用 DEFAULT_ACCOUNT，这里列表账号用 acc.id，
     * 与 LocalApi.providerRow 的 recordsOfProvider 排序口径一致（主账号恒排第一）。
     */
    private fun saveRecord(r: CheckinResult, accountKey: String) {
        try {
            HistoryStore(this).upsert(today(), r, accountKey)
        } catch (_: Exception) {
            // 落库失败不该让整个签到流程报错；账号卡上的 lastStatus 仍然有效
        }
    }

    /**
     * 取当前设置里的华为门户 Cookie。
     *
     * BUGS-v2.46 P3-3：多账号页原来恒传空串，而首页与后台任务都传 `s.hwPortalCookie`。
     * 门户 Cookie 是「签到同域读不到积分」时的兜底路径（`LocalCheckin.hwCreditPortal`），
     * 只在多账号页签就少了这一层，同一次签到在两处结果可能不一样。
     * 本页已有 `runBlocking` 用法（见 [traeDeviceForLogin]），这里保持一致。
     */
    private fun hwPortalCookieNow(): String = try {
        runBlocking { SettingsStore.flow(this@MultiAccountsActivity).first().hwPortalCookie }
    } catch (_: Exception) {
        ""
    }

    private fun checkOne(id: String, name: String) {
        statusBar.text = "签到中：$name…"
        progress.visibility = View.VISIBLE
        Thread {
            // 裸 Thread 里抛出的异常没人接 → 直接 crash（Android 主线程之外的未捕获异常
            // 同样会走默认 handler 杀掉进程）。整个签到体必须自带兜底。
            val msg = try {
                when (provider) {
                P_TRAE -> {
                    val acc = TraeAccountStore.all(this).firstOrNull { it.id == id }
                    if (acc == null) "账号不存在" else {
                        var renewed: String? = null
                        var renewedDev: String? = null
                        // 闸门：与首页一键签到 / 后台定时任务撞车时，同一账号只打一次上游
                        val r = CheckinGate.run("traesolo", acc.id) {
                            runBlocking {
                                LocalCheckin.trae(
                                    acc.token, acc.deviceId, acc.global, acc.session,
                                    onRenewed = { f -> renewed = f },
                                    onRenewedDevice = { f -> renewedDev = f },
                                )
                            }
                        }
                        renewed?.let { f -> TraeAccountStore.update(this, acc.copy(token = f)) }
                        renewedDev?.let { f -> TraeAccountStore.update(this, acc.copy(deviceId = f)) }
                        TraeAccountStore.update(this, acc.copy(
                            lastStatus = r.status, lastMessage = r.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r.totalPoints, streakDays = r.streakDays,
                            usedPoints = r.usedPoints))
                        saveRecord(r, acc.id)
                        r.message
                    }
                }
                P_QD -> {
                    val acc = QoderAccountStore.all(this).firstOrNull { it.id == id }
                    if (acc == null) "账号不存在" else {
                        // BUGS-v2.63-287 P3-1：续期换出的新 (token, refresh) 必须回写，
                        // 否则下次手动签到又拿旧 drt- 去换。
                        var qdTok: String? = null
                        var qdRef: String? = null
                        val r = CheckinGate.run("qoder", acc.id) {
                            runBlocking {
                                LocalCheckin.qoder(acc.region.ifBlank { "cn" }, acc.token,
                                    acc.uid, acc.machineId, "", acc.refresh,
                                    onRenewed = { t, rf -> qdTok = t; qdRef = rf })
                            }
                        }
                        QoderAccountStore.update(this, acc.copy(
                            lastStatus = r.status, lastMessage = r.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r.totalPoints, streakDays = r.streakDays,
                            usedPoints = r.usedPoints,
                            token = qdTok ?: acc.token,
                            refresh = qdRef ?: acc.refresh))
                        saveRecord(r, acc.id)
                        r.message
                    }
                }
                P_HW -> {
                    val acc = HwAccountStore.all(this).firstOrNull { it.id == id }
                    if (acc == null) "账号不存在" else {
                        val r = CheckinGate.run("huawei", acc.id) {
                            runBlocking { HwCheckin.run(this@MultiAccountsActivity, acc.cookie, hwPortalCookieNow()) }
                        }
                        HwAccountStore.update(this, acc.copy(
                            lastStatus = r.status, lastMessage = r.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r.totalPoints, streakDays = r.streakDays,
                            usedPoints = r.usedPoints))
                        saveRecord(r, acc.id)
                        r.message
                    }
                }
                else -> {
                    val acc = MmAccountStore.all(this).firstOrNull { it.id == id }
                    if (acc == null) "账号不存在" else {
                        val r = CheckinGate.run("minimax", acc.id) {
                            runBlocking {
                                LocalCheckin.minimax(acc.token, acc.userId, acc.deviceId, "")
                            }
                        }
                        MmAccountStore.update(this, acc.copy(
                            lastStatus = r.status, lastMessage = r.message,
                            lastCheckAt = System.currentTimeMillis(),
                            totalPoints = r.totalPoints, streakDays = r.streakDays,
                            usedPoints = r.usedPoints))
                        saveRecord(r, acc.id)
                        r.message
                    }
                }
            }
            } catch (e: Exception) {
                "签到异常：${e.message ?: e.javaClass.simpleName}"
            }
            runOnUiThread {
                // 页面可能已经关了：裸 Thread 跑完还去碰 progress/statusBar 会
                // 抛「View 已 attach 到已销毁的窗口」或直接泄漏整个 Activity。
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                statusBar.text = "$name：$msg"
                toast("$name：$msg")
                refreshList()
            }
        }.start()
    }

    private fun checkAll() {
        val list = rows()
        if (list.isEmpty()) { toast("还没有账号"); return }
        statusBar.text = "全部签到中（${list.size} 个账号）…"
        progress.visibility = View.VISIBLE
        Thread {
            var ok = 0
            // **Trae 多账号必须串行 + 错峰**：claim 按时间窗口排队，并发会互相挤成 9074。
            // 同类实测项目（L0NE-6/Trae-AutoCheckin）用 12~25 秒间隔。
            var traeFirst = true
            list.forEach { acc ->
                if (provider == P_TRAE) {
                    if (!traeFirst) {
                        val gap = 12_000L + (Math.random() * 13_000L).toLong()
                        try { Thread.sleep(gap) } catch (_: InterruptedException) {}
                    }
                    traeFirst = false
                }
                // 单个账号抛异常不能让整批中断（以前这里完全没 try，
                // 一个账号异常 → 线程未捕获异常 → 直接 crash）
                val okOne = try {
                    when (provider) {
                    P_TRAE -> {
                        val a = TraeAccountStore.all(this).firstOrNull { it.id == acc.id }
                        if (a == null) false else {
                            var renewed: String? = null
                            var renewedDev: String? = null
                            val r = CheckinGate.run("traesolo", a.id) {
                                runBlocking {
                                    LocalCheckin.trae(
                                        a.token, a.deviceId, a.global, a.session,
                                        onRenewed = { f -> renewed = f },
                                        onRenewedDevice = { f -> renewedDev = f },
                                    )
                                }
                            }
                            renewed?.let { f -> TraeAccountStore.update(this, a.copy(token = f)) }
                            renewedDev?.let { f -> TraeAccountStore.update(this, a.copy(deviceId = f)) }
                            TraeAccountStore.update(this, a.copy(
                                lastStatus = r.status, lastMessage = r.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r.totalPoints, streakDays = r.streakDays,
                                usedPoints = r.usedPoints))
                            saveRecord(r, a.id)
                            r.status == "success" || r.status == "already"
                        }
                    }
                    P_QD -> {
                        val a = QoderAccountStore.all(this).firstOrNull { it.id == acc.id }
                        if (a == null) false else {
                            // BUGS-v2.63-287 P3-1：批量签到时同样回写续期结果
                            var qdTok: String? = null
                            var qdRef: String? = null
                            val r = CheckinGate.run("qoder", a.id) {
                                runBlocking {
                                    LocalCheckin.qoder(a.region.ifBlank { "cn" }, a.token,
                                        a.uid, a.machineId, "", a.refresh,
                                        onRenewed = { t, rf -> qdTok = t; qdRef = rf })
                                }
                            }
                            QoderAccountStore.update(this, a.copy(
                                lastStatus = r.status, lastMessage = r.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r.totalPoints, streakDays = r.streakDays,
                                usedPoints = r.usedPoints,
                                token = qdTok ?: a.token,
                                refresh = qdRef ?: a.refresh))
                            saveRecord(r, a.id)
                            r.status == "success" || r.status == "already"
                        }
                    }
                    P_HW -> {
                        val a = HwAccountStore.all(this).firstOrNull { it.id == acc.id }
                        if (a == null) false else {
                            val r = CheckinGate.run("huawei", a.id) {
                                runBlocking { HwCheckin.run(this@MultiAccountsActivity, a.cookie, hwPortalCookieNow()) }
                            }
                            HwAccountStore.update(this, a.copy(
                                lastStatus = r.status, lastMessage = r.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r.totalPoints, streakDays = r.streakDays,
                                usedPoints = r.usedPoints))
                            saveRecord(r, a.id)
                            r.status == "success" || r.status == "already"
                        }
                    }
                    else -> {
                        val a = MmAccountStore.all(this).firstOrNull { it.id == acc.id }
                        if (a == null) false else {
                            val r = CheckinGate.run("minimax", a.id) {
                                runBlocking {
                                    LocalCheckin.minimax(a.token, a.userId, a.deviceId, "")
                                }
                            }
                            MmAccountStore.update(this, a.copy(
                                lastStatus = r.status, lastMessage = r.message,
                                lastCheckAt = System.currentTimeMillis(),
                                totalPoints = r.totalPoints, streakDays = r.streakDays,
                                usedPoints = r.usedPoints))
                            saveRecord(r, a.id)
                            r.status == "success" || r.status == "already"
                        }
                    }
                }
                } catch (e: Exception) {
                    false
                }
                if (okOne) ok++
            }
            runOnUiThread {
                if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.GONE
                statusBar.text = "全部完成：$ok/${list.size} 成功"
                toast("全部完成：$ok/${list.size}")
                refreshList()
            }
        }.start()
    }

    /**
     * 改显示名（用户 m02619）。
     *
     * 只写 `alias` 字段，不动 `nickname`（那是上游接口回来的，下次登录会被覆盖）。
     * 留空 = 清掉自定义名，回到自动名。四家各走各的 Store.update()，
     * 因为 update 是「按 id 整条替换」，只改 alias 不会碰凭据。
     */
    private fun rename(id: String, current: String) {
        SignDialog.input(
            this,
            kicker = "RENAME",
            title = "自定义名称",
            subtitle = "起一个自己好认的名字，比如「工作号」「小号」。\n留空保存则恢复自动名称（当前显示：$current）。",
            fields = listOf(SignDialog.Field("名称", initial = current.takeIf { it != autoNameOf(id) } ?: "")),
            okText = "保存",
        ) { v ->
            val alias = v[0].trim().take(24)
            val ok = when (provider) {
                P_TRAE -> TraeAccountStore.all(this).firstOrNull { it.id == id }?.let {
                    TraeAccountStore.update(this, it.copy(alias = alias)); true
                } ?: false
                P_QD -> QoderAccountStore.all(this).firstOrNull { it.id == id }?.let {
                    QoderAccountStore.update(this, it.copy(alias = alias)); true
                } ?: false
                P_HW -> HwAccountStore.all(this).firstOrNull { it.id == id }?.let {
                    HwAccountStore.update(this, it.copy(alias = alias)); true
                } ?: false
                else -> MmAccountStore.all(this).firstOrNull { it.id == id }?.let {
                    MmAccountStore.update(this, it.copy(alias = alias)); true
                } ?: false
            }
            if (ok) {
                refreshList()
                toast(if (alias.isBlank()) "已恢复自动名称" else "已改名为「$alias」")
            } else {
                toast("账号不存在，请返回重进")
            }
        }
    }

    /** 该账号的自动名（把 alias 当成空算出来的名字），用来判断输入框要不要预填。 */
    private fun autoNameOf(id: String): String = when (provider) {
        P_TRAE -> TraeAccountStore.all(this).firstOrNull { it.id == id }?.copy(alias = "")?.displayName() ?: ""
        P_QD -> QoderAccountStore.all(this).firstOrNull { it.id == id }?.copy(alias = "")?.displayName() ?: ""
        P_HW -> HwAccountStore.all(this).firstOrNull { it.id == id }?.copy(alias = "")?.displayName() ?: ""
        else -> MmAccountStore.all(this).firstOrNull { it.id == id }?.copy(alias = "")?.displayName() ?: ""
    }

    private fun confirmDelete(id: String, name: String) {
        SignDialog.confirm(
            this,
            kicker = "REMOVE",
            title = "删除账号",
            message = "确定删除「$name」？\n删除后需要重新网页登录才能加回来。",
            okText = "删除",
            danger = true,
        ) {
            when (provider) {
                P_TRAE -> TraeAccountStore.remove(this, id)
                P_QD -> QoderAccountStore.remove(this, id)
                P_HW -> {
                    // 删号前先清「今日已签」标记与验证结论（BUGS-v2.48 P3-6）：
                    // 两者的键都只由 HwAccount.identityOf 决定，删列表条目不影响它们。
                    // 于是「删掉账号 → 当天用同一份 Cookie 加回来」时，今日标记还在，
                    // HwCheckin.run 会直接返回 already 而不真签到 —— 用户以为加了号就签了。
                    val acc = HwAccountStore.all(this).firstOrNull { it.id == id }
                    if (acc != null) {
                        HwTodayMark.clear(this, acc.cookie)
                        HwVerified.clear(this, acc.cookie)
                    }
                    HwAccountStore.remove(this, id)
                }
                else -> MmAccountStore.remove(this, id)
            }
            refreshList()
            toast("已删除")
        }
    }

    /**
     * 主账号当前的 x-device-id（16 位数字），空/非法时返回空串。
     *
     * v2.6 起不再由它给新账号兜底：9074 的真实语义是「设备号在该账号下没有注册记录」，
     * 所以每个账号必须有自己的、且永久稳定的号 —— 由 [DeviceId.derive] 按凭据派生。
     * 这个函数只用于「展示/判断主账号有没有填」，不再用来给别的账号赋值。
     */
    private fun mainTraeDeviceId(): String {
        val d = try {
            runBlocking { SettingsStore.flow(this@MultiAccountsActivity).first().traeDeviceId }
        } catch (_: Exception) { "" }
        val t = d.trim()
        return if (DeviceId.isValid(t)) t else ""
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() { destroyed = true; super.onDestroy() }
}
