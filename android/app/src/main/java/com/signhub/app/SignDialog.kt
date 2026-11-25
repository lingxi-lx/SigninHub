package com.signhub.app

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 全软件统一的圆角弹窗。
 *
 * 为什么不用 `android.app.AlertDialog`：
 *   系统 AlertDialog 的窗口背景是 framework 里的九宫格图（Material 主题下是
 *   一块直角深灰板），改主题只能换颜色，**圆角改不动** —— 它在 `windowBackground`
 *   里画死了 padding 与描边，用户看到的就是截图里那种「灰色背景没有 R 角」。
 *   自己画一个 `Dialog`（window 背景透明 + 内部 GradientDrawable 圆角卡片）才能
 *   做到与主界面一致的 22dp 圆角、渐变主按钮、三套主题色。
 *
 * 三个入口覆盖全软件所有弹窗形态：
 *   [list]    选项列表（选区域、选操作）
 *   [confirm] 二次确认（删除、危险操作）
 *   [input]   表单（手填凭据、粘贴 token）
 *
 * 视觉口径与 `WbAccountsActivity` / `MultiAccountsActivity` 的主界面一致：
 *   卡片 #161A24 / 描边 rgba(120,140,200,.22) / 标题 19sp 粗体 / 正文 #C6CEE4 /
 *   主按钮 #6C8CFF→#9B6CFF 渐变胶囊 / 次按钮透明底细描边。
 */
object SignDialog {

    // ── 调色板（与多账号页、主界面同源） ──
    private val CARD = Color.parseColor("#161A24")
    private val CARD_TOP = Color.parseColor("#1B2030")
    private val LINE = Color.parseColor("#2E3648")
    private val TXT = Color.parseColor("#EEF1F8")
    private val MUTED = Color.parseColor("#A7B0C6")
    private val KICKER = Color.parseColor("#8FA4FF")
    private val BRAND = Color.parseColor("#6C8CFF")
    private val BRAND2 = Color.parseColor("#9B6CFF")
    private val OK = Color.parseColor("#37E0A6")
    private val DANGER = Color.parseColor("#FF6B81")
    private val FIELD_BG = Color.parseColor("#1B2130")

    /** 列表里的一行。 */
    data class Item(val text: String, val sub: String = "", val danger: Boolean = false)

    /** 表单里的一个输入框。 */
    data class Field(
        val hint: String,
        val initial: String = "",
        val multiline: Boolean = false,
        val numeric: Boolean = false,
    )

    // ────────────────────────── 列表 ──────────────────────────

    fun list(
        activity: Activity,
        kicker: String?,
        title: String,
        subtitle: String? = null,
        items: List<Item>,
        cancelText: String = "取消",
        onPick: (Int) -> Unit,
    ) {
        val dlg = base(activity)
        val card = card(activity)
        header(activity, card, kicker, title, subtitle)

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 14), dp(activity, 6), dp(activity, 14), dp(activity, 4))
        }
        items.forEachIndexed { i, it ->
            body.addView(row(activity, it) {
                dlg.dismiss()
                onPick(i)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(activity, 6) })
        }
        card.addView(body)

        footer(activity, card) {
            ghost(activity, cancelText) { dlg.dismiss() }
        }
        finish(activity, dlg, card)
    }

    // ────────────────────────── 确认 ──────────────────────────

    fun confirm(
        activity: Activity,
        kicker: String?,
        title: String,
        message: String,
        okText: String = "确定",
        cancelText: String = "取消",
        danger: Boolean = false,
        // 默认 null = 只关窗（原有行为不变）。需要「取消也要做事」的场景（例如登录前的
        // 风控提示，用户选了「先不登录」就得把 Activity 收掉）才传。
        //
        // 必须排在 onOk **之前**：调用方大量使用尾随 lambda（`confirm(...) { ... }`），
        // 尾随 lambda 绑的是最后一个参数。把它放最后会让所有老调用点的 lambda 变成
        // onCancel，报 `No value passed for parameter 'onOk'`。
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit,
    ) {
        val dlg = base(activity)
        val card = card(activity)
        header(activity, card, kicker, title, null)
        card.addView(TextView(activity).apply {
            text = message
            setTextColor(MUTED)
            textSize = 13.5f
            setLineSpacing(0f, 1.35f)
            setPadding(dp(activity, 20), dp(activity, 2), dp(activity, 20), dp(activity, 8))
        })
        footer(activity, card) {
            ghost(activity, cancelText) {
                dlg.dismiss()
                onCancel?.invoke()
            }
            primary(
                activity,
                okText,
                if (danger) listOf(DANGER, Color.parseColor("#E0495F")) else listOf(BRAND, BRAND2),
            ) {
                dlg.dismiss()
                onOk()
            }
        }
        finish(activity, dlg, card)
    }

    // ────────────────────────── 长按确认 ──────────────────────────

    /**
     * 只有一个「长按确认」按钮的弹窗（用户 m28007）。
     *
     * 与 [confirm] 的差别不只是少一颗按钮：
     *   · 没有「取消」—— 退出走**安卓返回键**（[Dialog.setOnCancelListener]），
     *     少一个并排按钮，正文和按钮都能拿到更宽的空间；
     *   · 主按钮通栏 + 52dp 高，按住时边框走进度（[HoldConfirmButton]），
     *     填满才回调，避免「没看完就顺手点掉」。
     *
     * `onCancel` 在返回键退出时触发（调用方通常在里面 `finish()` 掉整个页面）；
     * 点窗外不会关闭 —— 这个弹窗的语义是「必须先表态」。
     */
    fun holdConfirm(
        activity: Activity,
        kicker: String?,
        title: String,
        message: String,
        holdText: String,
        holdHint: String = "长按不放，填满边框即确认",
        confirmedText: String = "已确认",
        /**
         * 正文下方追加一条可点的链接（点开浏览器 / 长按复制）。
         *
         * 为什么单独一个 TextView 而不是把 URL 拼进 message 再用 Linkify：
         * 这类链接很长（带 utm 参数），拼进正文后会在任意位置折行、和正文混在一起，
         * 复制时容易把前后文字一起选中；单独一行既好点也好复制。
         *
         * **必须排在 onCancel / onOk 之前** —— 这两个后面天天被尾随 lambda 或命名参数调用，
         * 新参数插到尾部会把所有老调用点的 lambda 绑错位置（见 confirm 的同款注释）。
         */
        linkUrl: String? = null,
        onCancel: (() -> Unit)? = null,
        onOk: () -> Unit,
    ) {
        val dlg = base(activity)
        dlg.setCanceledOnTouchOutside(false)
        val card = card(activity)
        header(activity, card, kicker, title, null)
        card.addView(TextView(activity).apply {
            text = message
            setTextColor(MUTED)
            textSize = 13.5f
            setLineSpacing(dp(activity, 4).toFloat(), 1.38f)
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 18))
        })
        if (!linkUrl.isNullOrBlank()) {
            card.addView(TextView(activity).apply {
                text = linkUrl
                setTextColor(BRAND)
                textSize = 12.5f
                setLineSpacing(dp(activity, 3).toFloat(), 1.3f)
                // 正文下沿 18dp，这里回到 4dp：两块属于同一段话，别被间隙切断
                setPadding(dp(activity, 20), 0, dp(activity, 20), dp(activity, 18))
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                isLongClickable = true
                // 点一下：开浏览器去注册；长按：复制链接（没装浏览器 / 想发给别人时用）
                setOnClickListener {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                    } catch (_: Exception) {
                        copyLink(activity, linkUrl)
                        Toast.makeText(activity, "没有可用浏览器，链接已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    copyLink(activity, linkUrl)
                    Toast.makeText(activity, "链接已复制", Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 18), dp(activity, 2), dp(activity, 18), dp(activity, 18))
        }
        val btn = HoldConfirmButton(activity).apply {
            label = holdText
            subLabel = holdHint
            this.confirmedLabel = confirmedText
            onConfirmed = {
                dlg.dismiss()
                onOk()
            }
        }
        bar.addView(btn, LinearLayout.LayoutParams(MATCH, dp(activity, 58)))
        card.addView(bar)

        dlg.setOnCancelListener {
            dlg.dismiss()
            onCancel?.invoke()
        }
        finish(activity, dlg, card)
    }

    /** 复制链接到剪贴板；失败（极少数 ROM 限制）就静默，别让复制把弹窗搞崩。 */
    private fun copyLink(activity: Activity, url: String) {
        try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("link", url))
        } catch (_: Exception) {
        }
    }

    // ────────────────────────── 表单 ──────────────────────────

    /**
     * @param validate 返回非空字符串 = 校验失败，弹窗不关、在底部显示这行红字。
     *   旧代码用 `Toast("太短")` + `return@setPositiveButton`，用户刚填的内容会被一起
     *   关掉、必须从头再填；这里改成「留在原地提示」，是这次统一改版顺带修掉的体验问题。
     */
    fun input(
        activity: Activity,
        kicker: String?,
        title: String,
        subtitle: String? = null,
        fields: List<Field>,
        okText: String = "保存",
        cancelText: String = "取消",
        validate: ((List<String>) -> String?)? = null,
        onOk: (List<String>) -> Unit,
    ) {
        val dlg = base(activity)
        val card = card(activity)
        header(activity, card, kicker, title, subtitle)

        val err = TextView(activity).apply {
            setTextColor(DANGER)
            textSize = 12f
            visibility = View.GONE
            setPadding(dp(activity, 20), dp(activity, 2), dp(activity, 20), 0)
        }

        val edits = mutableListOf<EditText>()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 4), dp(activity, 18), dp(activity, 2))
        }
        fields.forEach { f ->
            val et = EditText(activity).apply {
                hint = f.hint
                setText(f.initial)
                setSelection(text.length)
                textSize = 13.5f
                setTextColor(TXT)
                setHintTextColor(Color.parseColor("#6C7790"))
                setPadding(dp(activity, 13), dp(activity, 11), dp(activity, 13), dp(activity, 11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(activity, 13).toFloat()
                    setColor(FIELD_BG)
                    setStroke(dp(activity, 1), LINE)
                }
                if (f.multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 4
                    setSingleLine(false)
                } else {
                    inputType = if (f.numeric) InputType.TYPE_CLASS_NUMBER
                    else InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                }
            }
            edits.add(et)
            body.addView(et, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(activity, 8)
            })
        }
        card.addView(body)
        card.addView(err)

        footer(activity, card) {
            ghost(activity, cancelText) { dlg.dismiss() }
            primary(activity, okText, listOf(BRAND, BRAND2)) {
                val values = edits.map { it.text.toString().trim() }
                val bad = validate?.invoke(values)
                if (!bad.isNullOrBlank()) {
                    err.text = bad
                    err.visibility = View.VISIBLE
                    return@primary
                }
                dlg.dismiss()
                onOk(values)
            }
        }
        finish(activity, dlg, card)
        edits.firstOrNull()?.let { et ->
            card.post { et.requestFocus() }
        }
    }

    // ────────────────────────── 骨架 ──────────────────────────

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(a: Activity, v: Int): Int =
        (v * a.resources.displayMetrics.density).toInt()

    private fun base(activity: Activity): Dialog {
        val d = Dialog(activity, R.style.Theme_SigninHub_Dialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCanceledOnTouchOutside(true)
        return d
    }

    /**
     * 圆角卡片。上下两段渐变让顶部有一点光感，避免整块死板的纯色
     * —— 这也是用户抱怨「灰色背景」的直接原因。
     */
    private fun card(activity: Activity): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(CARD_TOP, CARD))
            .apply {
                cornerRadius = dp(activity, 22).toFloat()
                setStroke(dp(activity, 1), LINE)
            }
        clipToOutline = false
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
    }

    private fun header(
        activity: Activity,
        card: LinearLayout,
        kicker: String?,
        title: String,
        subtitle: String?,
    ) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 6))
        }
        if (!kicker.isNullOrBlank()) {
            box.addView(TextView(activity).apply {
                text = kicker.uppercase()
                setTextColor(KICKER)
                textSize = 9.5f
                letterSpacing = 0.24f
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        box.addView(TextView(activity).apply {
            text = title
            setTextColor(TXT)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(activity, if (kicker.isNullOrBlank()) 0 else 6), 0, 0)
        })
        if (!subtitle.isNullOrBlank()) {
            box.addView(TextView(activity).apply {
                text = subtitle
                setTextColor(MUTED)
                textSize = 12.5f
                setLineSpacing(0f, 1.32f)
                setPadding(0, dp(activity, 7), 0, 0)
            })
        }
        card.addView(box)
    }

    private fun row(activity: Activity, item: Item, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 15), dp(activity, 13), dp(activity, 13), dp(activity, 13))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                setColor(Color.parseColor("#1C2231"))
                setStroke(dp(activity, 1), Color.parseColor("#2A3345"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }.start()
                onClick()
            }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                addView(TextView(activity).apply {
                    text = item.text
                    setTextColor(if (item.danger) DANGER else TXT)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                })
                if (item.sub.isNotBlank()) {
                    addView(TextView(activity).apply {
                        text = item.sub
                        setTextColor(MUTED)
                        textSize = 11.5f
                        setPadding(0, dp(activity, 3), 0, 0)
                    })
                }
            })
            addView(TextView(activity).apply {
                text = "›"
                setTextColor(MUTED)
                textSize = 18f
                setPadding(dp(activity, 8), 0, 0, 0)
            })
        }

    private fun footer(activity: Activity, card: LinearLayout, build: LinearLayout.() -> Unit) {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 16))
        }
        bar.build()
        card.addView(bar)
    }

    /**
     * 次按钮 / 主按钮。
     *
     * **必须是 `LinearLayout` 的扩展函数，内部自己 `addView`** —— 早期版本写成
     * `fun ghost(...): View = pill(...)`，`footer { bar.build() }` 里调完就把返回值丢了，
     * 于是每个弹窗的按钮栏都是空的：确认框只剩标题和正文，用户找不到「删除/确定」
     * （用户 m26250 报的就是这个，截图里那两颗按钮其实是背后账号卡片透出来的）。
     */
    private fun LinearLayout.ghost(activity: Activity, text: String, onClick: () -> Unit) {
        addView(
            pill(activity, text, listOf(Color.parseColor("#20273A"), Color.parseColor("#20273A")),
                textColor = MUTED, stroke = LINE, onClick = onClick)
        )
    }

    private fun LinearLayout.primary(
        activity: Activity,
        text: String,
        colors: List<Int>,
        onClick: () -> Unit,
    ) {
        addView(pill(activity, text, colors, textColor = Color.WHITE, stroke = 0, onClick = onClick))
    }

    private fun pill(
        activity: Activity,
        text: String,
        colors: List<Int>,
        textColor: Int,
        stroke: Int,
        onClick: () -> Unit,
    ): View = FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(dp(activity, 108), dp(activity, 42)).apply {
            leftMargin = dp(activity, 8)
        }
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors.toIntArray()).apply {
            cornerRadius = dp(activity, 13).toFloat()
            if (stroke != 0) setStroke(dp(activity, 1), stroke)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }.start()
            onClick()
        }
        addView(TextView(activity).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        })
    }

    /**
     * 装进 Dialog 并显示：透明窗口 + 左右留白 + 高度上限 + 入场动画。
     *
     * 高度上限是必须的：手填凭据那种表单在横屏或小屏上会超出屏幕，没有上限时
     * Dialog 会把内容顶出可视区，底部按钮点不到。
     */
    private fun finish(activity: Activity, dlg: Dialog, card: LinearLayout) {
        val root = FrameLayout(activity).apply {
            setPadding(dp(activity, 22), dp(activity, 10), dp(activity, 22), dp(activity, 10))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            clipToPadding = false
        }
        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, WRAP))
        dlg.setContentView(root)

        dlg.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.setLayout(MATCH, WRAP)
            w.setGravity(Gravity.CENTER)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.62f)
        }
        dlg.setOnShowListener {
            val cap = (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            card.post {
                val h = scroll.height
                if (h > cap) {
                    scroll.layoutParams = (scroll.layoutParams as FrameLayout.LayoutParams).apply {
                        height = cap
                    }
                    scroll.requestLayout()
                }
                card.alpha = 0f
                card.scaleX = 0.94f
                card.scaleY = 0.94f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(180).start()
            }
        }
        dlg.show()
    }
}
