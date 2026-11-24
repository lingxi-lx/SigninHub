package com.signhub.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 长按确认按钮：按住时**沿边框**填充进度，未填满就松手会回弹清空，填满后变「已确认」，
 * 同时**按钮自身抖一下**并伴随一次轻震。
 *
 * 为什么不用 `setOnLongClickListener`：
 *   系统长按只有「到点 / 没到点」两个状态，用户看不到还要按多久，中途松手也没有任何反馈。
 *   这个按钮把「按住」这件事变成可见的进度：边框从 0 走到 1，用户知道自己在做什么，
 *   松早了也只是回弹，不会误触发（Trae 风控提示这种「看完再走」的弹窗正需要这个语义）。
 *
 * 进度为什么画在边框上而不是填充条：
 *   弹窗里已经有渐变主按钮的视觉语言，内部再放一条进度条会像两个控件叠在一起。
 *   用 `PathMeasure` 沿圆角矩形的轮廓取一段描边，进度天然贴着按钮的形状走，不额外占空间。
 *
 * 文字为什么分两行：
 *   用户 m28007 明确要求「按钮和字体保留一定空间看上去不挤」。主文案「已阅读，继续登录」
 *   单独一行（14.5sp 粗体），下面一行 9.5sp 的操作提示（[subLabel]）告诉用户要按住 ——
 *   把「怎么操作」和「点下去代表什么」拆成两层，比塞成一行长句好读，也不会顶到边框。
 *
 * 为什么除了系统震动还要「按钮自己抖」（用户 m28012）：
 *   震动是**手机**在动，眼睛看不见 —— 手机放在桌上、戴壳、或系统关了触摸振动时，用户完全
 *   接收不到「已确认」这个信息。所以确认时同时做三件事，视觉、触觉各来一遍：
 *     ① 整块按钮横向抖动（衰减振荡，7dp 起，60ms 一步收窄）；
 *     ② 内部闪一下白光（[flashAlpha] 0→1→0）；
 *     ③ 一圈描边从中心向外扩散并淡出（[ringP]），像水波。
 *   按住过程中每过 25% 还会轻微「卡」一下（[tick]），让「正在积累」这件事也有触感。
 *
 * 交互细节：
 *   · 按下后手指移出按钮范围 → 立刻回弹（等同松手），移回来重新开始；
 *   · 竖直拖动被外层 `ScrollView` 抢走时会收到 `ACTION_CANCEL`，同样回弹，不会卡在半个进度；
 *   · 填满后延迟 [dismissDelay] 再回调，让抖动、闪光、换字都演完 —— 太早关窗会把这些全剪掉。
 */
class HoldConfirmButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val BRAND = Color.parseColor("#6C8CFF")
    private val BRAND2 = Color.parseColor("#9B6CFF")
    private val OK = Color.parseColor("#37E0A6")
    private val OK2 = Color.parseColor("#2BC78F")
    private val TRACK = Color.parseColor("#4DFFFFFF")
    private val PROGRESS = Color.parseColor("#EAF1FF")

    /** 边框描边宽度（dp）。2.5 在 1080p 上约 7px，够粗但不糊。 */
    private var strokeDp = 2.5f
    private var radiusDp = 15f

    /** 按满全程需要多久。1100ms 比系统长按（500ms）长一倍，是「读过了」而不是「点了一下」。 */
    var holdMs: Long = 1100L

    /** 填满后到回调的延迟。要盖住抖动 + 闪光 + 水波（约 360ms），否则动画被关窗剪断。 */
    var dismissDelay: Long = 420L

    /** 按钮文字（未确认态）。 */
    var label: CharSequence = "确定"
        set(v) {
            field = v
            main.text = v
            contentDescription = v
        }

    /** 副文案：告诉用户怎么操作。空串则不占位。 */
    var subLabel: CharSequence = ""
        set(v) {
            field = v
            sub.text = v
            sub.visibility = if (v.isBlank()) View.GONE else View.VISIBLE
        }

    /** 填满后的文字。 */
    var confirmedLabel: CharSequence = "已确认"

    /** 填满并完成动画后回调。 */
    var onConfirmed: (() -> Unit)? = null

    private var progress = 0f
    private var holding = false
    private var confirmed = false
    private var anim: ValueAnimator? = null

    /** 缩放脉冲（按下缩、确认弹）。独立于 [anim]（那是进度），两者要同时跑。 */
    private var scaleAnim: ValueAnimator? = null

    /** 横移抖动。同样独立，进度在跑的时候也可能触发 [tick]。 */
    private var shakeAnim: ValueAnimator? = null

    /** 确认瞬间的内部白光，0→1→0。 */
    private var flashAlpha = 0f

    /** 确认瞬间从中心向外扩散的描边水波，0→1。 */
    private var ringP = 1f

    /** 按住过程里已经「卡」过的 25% 里程碑，避免同一档重复抖。 */
    private var lastTick = 0

    private val trackPath = Path()
    private val segPath = Path()
    private val measure = PathMeasure()
    private val rect = RectF()
    private val inner = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val main = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private val sub = TextView(context).apply {
        setTextColor(Color.parseColor("#D8E2FF"))
        textSize = 9.5f
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        includeFontPadding = false
        visibility = View.GONE
    }

    /** 两行文字整体：确认后一起淡出、换文案、淡入，避免只换一行留下残影。 */
    private val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        // 左右各留 22dp：边框描边 + 圆角在内侧，文字不贴边，视觉上「不挤」
        setPadding(dp(22f).toInt(), 0, dp(22f).toInt(), 0)
        addView(main, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(sub, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3f).toInt()
        })
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    init {
        // ViewGroup 默认 WILL_NOT_DRAW，不清掉这个标志 onDraw 不会被调用（边框就画不出来）
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        addView(box)
        applyFill()
    }

    // ────────────────────────── 绘制 ──────────────────────────

    private fun applyFill() {
        val colors = if (confirmed) intArrayOf(OK, OK2) else intArrayOf(BRAND, BRAND2)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radiusDp)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sw = dp(strokeDp)
        stroke.strokeWidth = sw
        val inset = sw / 2f + dp(1f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = (dp(radiusDp) - inset).coerceAtLeast(dp(2f))

        trackPath.reset()
        trackPath.addRoundRect(rect, r, r, Path.Direction.CW)

        // 确认瞬间的内部白光：从中心铺满整块，快速淡出。比只换底色更容易被眼睛抓到。
        // 必须画在轨道**之前** —— 画在后面会把那圈描边一起盖白，确认的一瞬间看起来像按钮糊了。
        if (flashAlpha > 0.001f) {
            fill.color = Color.WHITE
            fill.alpha = (flashAlpha * 150f).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, r, r, fill)
        }

        // 轨道：一圈半透明白线，让用户知道「要填的是这一整圈」
        stroke.color = if (confirmed) Color.parseColor("#66FFFFFF") else TRACK
        canvas.drawPath(trackPath, stroke)

        if (progress > 0.001f) {
            measure.setPath(trackPath, false)
            val len = measure.length
            segPath.reset()
            measure.getSegment(0f, len * progress.coerceIn(0f, 1f), segPath, true)
            stroke.color = if (confirmed) Color.WHITE else PROGRESS
            canvas.drawPath(segPath, stroke)
        }

        // 水波：从中心向外扩散的一圈描边，越往外越细越淡，跑到边界为止。
        // 用 clipPath 裁到按钮形状里 —— 否则扩散到边缘会画到圆角外面，看起来像溢出的方块。
        if (ringP < 0.999f) {
            val p = ringP.coerceIn(0f, 1f)
            val k = 1f - (1f - p) * (1f - p)          // 先快后慢
            val grow = dp(4f) + width * 0.62f * k
            val cx = width / 2f
            val cy = height / 2f
            inner.set(cx - grow, cy - grow, cx + grow, cy + grow)
            stroke.strokeWidth = (dp(strokeDp) * (1.4f - p)).coerceAtLeast(dp(0.8f))
            stroke.color = Color.WHITE
            stroke.alpha = ((1f - p) * 190f).toInt().coerceIn(0, 255)
            val save = canvas.save()
            canvas.clipPath(trackPath)
            canvas.drawRoundRect(inner, grow, grow, stroke)
            canvas.restoreToCount(save)
        }
    }

    // ────────────────────────── 触摸 ──────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (confirmed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressIn()
                startHold()
            }
            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0f && event.x <= width && event.y >= 0f && event.y <= height
                if (inside && !holding) {
                    pressIn()
                    startHold()
                } else if (!inside && holding) {
                    cancelHold()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                cancelHold()
            }
        }
        return true
    }

    /** 按下时先缩一点，手指有「压进去」的实感。 */
    private fun pressIn() {
        scaleAnim?.cancel()
        scaleAnim = ValueAnimator.ofFloat(scaleX, 0.975f).apply {
            duration = 90L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                scaleX = s
                scaleY = s
            }
            start()
        }
    }

    private fun startHold() {
        if (confirmed || holding) return
        holding = true
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = ((1f - progress) * holdMs).toLong().coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                progress = (a.animatedValue as Float).coerceIn(0f, 1f)
                val m = (progress * 4f).toInt()
                if (m > lastTick) {
                    lastTick = m
                    if (progress < 0.999f) tick()
                }
                invalidate()
                if (progress >= 1f && !confirmed) complete()
            }
            start()
        }
    }

    /** 松手 / 移出 / 被外层抢走：进度回弹到 0，缩放也回到 1。 */
    private fun cancelHold() {
        holding = false
        lastTick = 0
        if (confirmed) return
        anim?.cancel()
        anim = null
        // 弹回原尺寸用 Overshoot：先冲过 1 一点再落回，手感比匀速回弹「活」
        scaleAnim?.cancel()
        scaleAnim = ValueAnimator.ofFloat(scaleX, 1f).apply {
            duration = 150L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                scaleX = s
                scaleY = s
            }
            start()
        }
        val from = progress
        if (from <= 0.001f) {
            progress = 0f
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(from, 0f).apply {
            duration = (140f + 120f * from).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                progress = (a.animatedValue as Float).coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

    /** 每过 25% 的一下轻「卡」：2dp 左右来回，90ms。幅度小到不打断阅读，但手上看得见。 */
    private fun tick() {
        shakeAnim?.cancel()
        shakeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                // 一个来回：0 → -2dp → 0
                translationX = -dp(2f) * (1f - Math.abs(p * 2f - 1f))
            }
            start()
        }
    }

    private fun complete() {
        if (confirmed) return
        confirmed = true
        holding = false
        anim?.cancel()
        anim = null
        progress = 1f
        applyFill()
        invalidate()

        // ① 整块按钮抖一下：左右衰减振荡，眼睛能直接看到「成了」
        shake()
        // ② 内部白光 + ③ 向外水波
        flash()
        startRing()
        // ④ 文字换掉
        box.animate().cancel()
        box.animate().alpha(0f).setDuration(80).withEndAction {
            main.text = confirmedLabel
            sub.visibility = View.GONE
            box.animate().alpha(1f).setDuration(150).start()
        }.start()
        // ⑤ 手机也震一下
        buzz()
        postDelayed({ onConfirmed?.invoke() }, dismissDelay)
    }

    /**
     * 抖动本体。
     *
     * 三条曲线同时跑，各管一个属性 —— **不能用 `ViewPropertyAnimator` 串起来**：
     * `animate()` 每次返回的是**同一个实例**，第二次 `.scaleX().start()` 会把上一次的
     * 属性集和 `withEndAction` 一起顶掉，表现为「只抖了第一下就停」。所以全部走
     * 独立的 `ValueAnimator`，互不干扰，也方便在 `onDetachedFromWindow` 里统一取消。
     *
     *   · 横移：7dp → -5dp → 3dp → -1.5dp → 0，五段衰减，总 270ms（像被弹了一下）
     *   · 缩放：1 → 1.045 → 1，先 Overshoot 冲上去再缓落，补上「弹」的感觉
     *   · 白光 + 水波在 [flash] / [startRing] 里
     */
    private fun shake() {
        val path = floatArrayOf(0f, 1f, -0.72f, 0.43f, -0.21f, 0f)
        shakeAnim?.cancel()
        shakeAnim = ValueAnimator.ofFloat(*path).apply {
            duration = 270L
            addUpdateListener { a ->
                translationX = (a.animatedValue as Float) * dp(7f)
            }
            start()
        }
        scaleAnim?.cancel()
        scaleAnim = ValueAnimator.ofFloat(scaleX, 1.045f).apply {
            duration = 110L
            interpolator = OvershootInterpolator(2f)
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                scaleX = s
                scaleY = s
            }
            // ValueAnimator 没有 withEndAction（那是 ViewPropertyAnimator 的），用 listener
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scaleAnim = ValueAnimator.ofFloat(scaleX, 1f).apply {
                        duration = 190L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { a ->
                            val s = a.animatedValue as Float
                            scaleX = s
                            scaleY = s
                        }
                        start()
                    }
                }
            })
            start()
        }
    }

    /** 内部白光 0→1→0，180ms 打完。 */
    private fun flash() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                // 前 25% 快速亮起，后 75% 缓慢消退
                flashAlpha = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
                invalidate()
            }
            start()
        }
    }

    /** 水波：ringP 从 0 走到 1，onDraw 里按它算扩散半径。 */
    private fun startRing() {
        ringP = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                ringP = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 轻震一下。用 Vibrator 而不是 `performHapticFeedback`：
     * 后者受「触摸振动」系统开关控制，很多人关着就完全没反馈；这里是一次明确的状态确认，
     * 28ms / 幅度 90（255 满档）足够轻，不会像来电那样吓人。
     *
     * API 31 起 `VIBRATOR_SERVICE` 废弃（多振动器设备要经 `VibratorManager` 取默认那一个），
     * 两条路都写，避免高版本上取到 null 直接没反馈。
     */
    private fun buzz() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // 两段式：先 18ms 重一点、隔 40ms 再 26ms 轻一点，比单次长震更像「咔哒」确认音
                    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 40, 26), intArrayOf(0, 120, 0, 70), -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(0, 18, 40, 26), -1)
                }
                return
            }
        } catch (_: Throwable) {
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        scaleAnim?.cancel()
        shakeAnim?.cancel()
        super.onDetachedFromWindow()
    }
}
