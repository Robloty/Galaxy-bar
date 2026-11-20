package com.evan.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class IslandService : Service() {
    private enum class Mode { IDLE, NOTIF, OPEN, CHARGE }

    private lateinit var sp: SharedPreferences
    private lateinit var cfg: Cfg
    private lateinit var wm: WindowManager
    private lateinit var msm: MediaSessionManager
    private lateinit var cm: CameraManager
    private lateinit var root: FrameLayout
    private lateinit var shell: FrameLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bg: GlassDrawable
    private val h = Handler(Looper.getMainLooper())

    private var mode = Mode.IDLE
    private var lastMode: Mode? = null
    private var fx: Array<RenderEffect?>? = null

    // ressorts
    private val holderW = FloatValueHolder(0f)
    private val holderH = FloatValueHolder(0f)
    private lateinit var springW: SpringAnimation
    private lateinit var springH: SpringAnimation
    private var curW = 0f
    private var curH = 0f
    private var tgtW = 1
    private var tgtH = 1
    private var bigR = 1000f
    private var colorAnim: ValueAnimator? = null
    private var enterAnim: ValueAnimator? = null

    private var notif: NotifData? = null
    private var chargeLevel = 0
    private var ctrl: MediaController? = null
    private var torchId: String? = null
    private var lampOn = false
    private var running = false
    private var acc = 0L
    private var last = 0L
    private var hooked = false

    private val timers = LinkedHashMap<String, TimerInfo>()
    private val timerViews = HashMap<String, TextView>()
    private var chronoTv: TextView? = null
    private var idleChronoTv: TextView? = null
    private var progress: ProgressBar? = null
    private var posTv: TextView? = null

    private var pending: Cfg? = null
    private var previewQueued = false

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.toFloat()
    }

    private fun withAlpha(c: Int, pct: Int) =
        (c and 0x00FFFFFF) or ((pct.coerceIn(0, 100) * 255 / 100) shl 24)

    private fun txt(s: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun circleBtn(label: String, color: Int, size: Int = 48, onClick: (TextView) -> Unit) =
        txt(label, 18f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick(this) }
        }

    private fun measureW(v: View): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return v.measuredWidth
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun chronoMs() =
        if (running) acc + (SystemClock.elapsedRealtime() - last) else acc

    private fun timerText(t: TimerInfo): String {
        if (t.chrono && t.base > 0) {
            val now = System.currentTimeMillis()
            val ms = if (t.countDown) t.base - now else now - t.base
            return fmt(ms.coerceAtLeast(0))
        }
        return t.text.ifBlank { t.title }
    }

    private fun timerLabel(): String? = when {
        timers.isNotEmpty() -> timerText(timers.values.first())
        running || acc > 0 -> fmt(chronoMs())
        else -> null
    }

    private fun mediaPos(): Long {
        val s = ctrl?.playbackState ?: return 0
        if (s.state == PlaybackState.STATE_PLAYING) {
            val d = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            return (s.position + d * s.playbackSpeed).toLong()
        }
        return s.position
    }

    private fun batteryPct(): Int {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val l = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val s = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return l * 100 / s
    }

    private fun onTap(f: (() -> Unit)?) {
        shell.setOnClickListener(f?.let { fn -> View.OnClickListener { fn() } })
        shell.isClickable = f != null
    }

    private fun fxLevel(i: Int): RenderEffect? {
        if (i <= 0 || Build.VERSION.SDK_INT < 31) return null
        val arr = fx ?: Array<RenderEffect?>(4) { k ->
            val b = (k + 1) * 2.5f * resources.displayMetrics.density
            RenderEffect.createBlurEffect(b, b, Shader.TileMode.CLAMP)
        }.also { fx = it }
        return arr[i - 1]
    }

    // ---------- préférences ----------
    private fun applyCfg(n: Cfg) {
        if (n == cfg) return
        val rebuild = n.copy(posX = 0, posY = 0) != cfg.copy(posX = 0, posY = 0)
        cfg = n
        applyWindow()
        if (rebuild) render()
    }

    private val applyPrefs = Runnable { applyCfg(Cfg.load(sp)) }

    private val previewRun = Runnable {
        previewQueued = false
        val n = pending ?: return@Runnable
        pending = null
        applyCfg(n)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "running") {
            h.removeCallbacks(applyPrefs)
            h.postDelayed(applyPrefs, 30)
        }
    }

    private fun applyWindow() {
        lp.x = dp(cfg.posX)
        lp.y = dp(cfg.posY)
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    private fun setWindow(w: Int, hh: Int) {
        if (lp.width == w && lp.height == hh) return
        lp.width = w
        lp.height = hh
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    private fun reserveWindow() {
        val m = if (cfg.bounce > 0) dp(16) else 0
        setWindow(max(curW.toInt(), tgtW) + m, max(curH.toInt(), tgtH) + m)
    }

    private fun settle() {
        if (!springW.isRunning && !springH.isRunning) setWindow(tgtW, tgtH)
    }

    // ---------- ressorts ----------
    private fun makeSprings() {
        springW = SpringAnimation(holderW).setSpring(SpringForce(0f))
        springH = SpringAnimation(holderH).setSpring(SpringForce(0f))
        springW.addUpdateListener { _, v, _ -> curW = v; applyShell() }
        springH.addUpdateListener { _, v, _ -> curH = v; applyShell() }
        val end = DynamicAnimation.OnAnimationEndListener { _, canceled, _, _ ->
            if (!canceled) settle()
        }
        springW.addEndListener(end)
        springH.addEndListener(end)
    }

    private fun configureSprings(expanding: Boolean) {
        val s = cfg.speed / 100f
        val k = (380f * s * s).coerceIn(120f, 2400f)
        val z = (1f - cfg.bounce / 100f * (if (expanding) 0.5f else 0.3f)).coerceIn(0.45f, 1f)
        springW.spring.setStiffness(k).setDampingRatio(z)
        springH.spring.setStiffness(k * 0.9f).setDampingRatio(z)
    }

    private fun applyShell() {
        val p = shell.layoutParams as FrameLayout.LayoutParams
        val w = curW.toInt().coerceAtLeast(1)
        val hh = curH.toInt().coerceAtLeast(1)
        if (p.width != w || p.height != hh) {
            p.width = w
            p.height = hh
            shell.layoutParams = p
        }
        bg.radius = min(bigR, hh / 2f)
    }

    private fun snap(w: Float, hh: Float) {
        springW.cancel()
        springH.cancel()
        holderW.value = w
        holderH.value = hh
        curW = w
        curH = hh
        applyShell()
    }

    // ---------- boucle temps réel ----------
    private val tick = object : Runnable {
        override fun run() {
            chronoTv?.text = fmt(chronoMs())
            idleChronoTv?.text = timerLabel() ?: ""
            for ((k, tv) in timerViews) timers[k]?.let { tv.text = timerText(it) }
            val d = ctrl?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val pb = progress
            if (pb != null && d > 0) {
                val p = mediaPos()
                pb.progress = (p * 1000 / d).toInt().coerceIn(0, 1000)
                posTv?.text = fmt(p) + " / " + fmt(d)
            }
            h.postDelayed(this, 250)
        }
    }

    // ---------- musique ----------
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshIfCalm()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshIfCalm()
    }

    private fun refreshIfCalm() {
        if (mode == Mode.IDLE || mode == Mode.OPEN) render()
    }

    private fun pick(list: List<MediaController>) {
        val n = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (n?.sessionToken == ctrl?.sessionToken) return
        ctrl?.unregisterCallback(cb)
        ctrl = n
        n?.registerCallback(cb, h)
        refreshIfCalm()
    }

    private fun refreshMedia() {
        try {
            pick(msm.getActiveSessions(ComponentName(this, NotifListener::class.java)))
        } catch (e: SecurityException) {
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { l -> pick(l ?: emptyList()) }

    private fun hookMedia() {
        if (!hooked) {
            try {
                msm.addOnActiveSessionsChangedListener(
                    sessionsListener, ComponentName(this, NotifListener::class.java)
                )
                hooked = true
            } catch (e: SecurityException) {
            }
        }
        refreshMedia()
    }

    // ---------- lampe ----------
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            torchId = cameraId
            lampOn = enabled
            refreshIfCalm()
        }
    }

    // ---------- charge ----------
    private val rec = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (cfg.chargeOn && i.action == Intent.ACTION_POWER_CONNECTED) {
                chargeLevel = batteryPct()
                flash(Mode.CHARGE, 3000)
            }
        }
    }

    // ---------- états temporaires ----------
    private val back = Runnable {
        if (mode == Mode.NOTIF || mode == Mode.CHARGE) {
            mode = Mode.IDLE; render()
        }
    }

    private fun flash(m: Mode, ms: Long) {
        if (mode == Mode.OPEN) return
        mode = m
        render()
        h.removeCallbacks(back)
        h.postDelayed(back, ms)
    }

    private fun close() {
        h.removeCallbacks(back)
        mode = Mode.IDLE
        render()
    }

    // ---------- gestes ----------
    private val gd by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if ((mode == Mode.OPEN || mode == Mode.NOTIF) && vy < -700f && abs(vy) > abs(vx)) {
                    close()
                    return true
                }
                return false
            }
        })
    }

    // ---------- cycle de vie ----------
    override fun onCreate() {
        super.onCreate()
        sp = Prefs.sp(this)
        cfg = Cfg.load(sp)
        sp.edit().putBoolean("running", true).apply()
        sp.registerOnSharedPreferenceChangeListener(prefListener)
        startAsForeground()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        msm = getSystemService(MediaSessionManager::class.java)
        cm = getSystemService(CameraManager::class.java)

        bg = GlassDrawable(0xFF000000.toInt(), 0xFF000000.toInt(), false, false,
            resources.displayMetrics.density)
        shell = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            background = bg
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, min(bigR, v.height / 2f))
                }
            }
            setOnTouchListener { v, e ->
                gd.onTouchEvent(e)
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> if (v.isClickable) {
                        v.pivotX = v.width / 2f
                        v.pivotY = 0f
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(110).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(OvershootInterpolator(2f)).start()
                }
                false
            }
        }
        root = FrameLayout(this).apply {
            addView(shell)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE && mode == Mode.OPEN) close()
                false
            }
        }
        lp = WindowManager.LayoutParams(
            dp(1), dp(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        applyWindow()
        wm.addView(root, lp)
        makeSprings()

        Bus.notif = { n ->
            if (cfg.notifOn) {
                notif = n
                flash(Mode.NOTIF, cfg.notifSec * 1000L)
            }
        }
        Bus.timer = { t ->
            h.post {
                val isNew = !timers.containsKey(t.key)
                timers[t.key] = t
                if (isNew && cfg.chronoOn && mode != Mode.OPEN) {
                    notif = NotifData(
                        t.title.ifBlank { "Minuteur" }, "En cours", t.icon, t.intent, timerText(t)
                    )
                    flash(Mode.NOTIF, cfg.notifSec * 1000L)
                } else {
                    refreshIfCalm()
                }
            }
        }
        Bus.timerGone = { k ->
            h.post { if (timers.remove(k) != null) refreshIfCalm() }
        }
        Bus.connected = {
            hookMedia()
            NotifListener.inst?.scan()
        }
        Bus.preview = { n ->
            pending = n
            if (!previewQueued) {
                previewQueued = true
                root.postOnAnimation(previewRun)
            }
        }
        Bus.cmd = { c ->
            h.post {
                when (c) {
                    "open" -> { refreshMedia(); mode = Mode.OPEN; render() }
                    "notif" -> {
                        notif = NotifData(
                            "Island", "Ceci est une notification de test",
                            applicationInfo.loadIcon(packageManager), null
                        )
                        flash(Mode.NOTIF, cfg.notifSec * 1000L)
                    }
                    "charge" -> { chargeLevel = batteryPct(); flash(Mode.CHARGE, 3000) }
                    "chrono" -> {
                        if (running || acc > 0) {
                            running = false; acc = 0
                        } else {
                            last = SystemClock.elapsedRealtime(); running = true
                        }
                        refreshIfCalm()
                    }
                }
            }
        }
        cm.registerTorchCallback(torchCb, h)
        ContextCompat.registerReceiver(
            this, rec, IntentFilter(Intent.ACTION_POWER_CONNECTED),
            ContextCompat.RECEIVER_EXPORTED
        )
        hookMedia()
        NotifListener.inst?.scan()
        render()
        h.post(tick)
    }

    // ---------- rendu ----------
    private fun applyStyle(m: Mode, animate: Boolean) {
        val tint = TINTS[cfg.tint.coerceIn(0, TINTS.lastIndex)]
        val solid = cfg.style == 0 || m == Mode.IDLE || m == Mode.CHARGE
        var top = 0xFF000000.toInt()
        var bottom = 0xFF000000.toInt()
        var sheen = false
        var border = false
        if (!solid) {
            if (m == Mode.OPEN) {
                top = tint.top; bottom = tint.bottom
            } else {
                top = 0xFF26262E.toInt(); bottom = 0xFF050507.toInt()
            }
            sheen = true
            border = cfg.border
            if (cfg.style == 2) {
                top = withAlpha(top, cfg.opacity)
                bottom = withAlpha(bottom, cfg.opacity)
            }
        }
        bg.sheen = sheen
        bg.border = border
        colorAnim?.cancel()
        if (!animate) {
            bg.setColors(top, bottom)
            return
        }
        val t1 = top
        val b1 = bottom
        val t0 = bg.top
        val b0 = bg.bottom
        val ev = ArgbEvaluator()
        colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            addUpdateListener { a ->
                val f = a.animatedFraction
                bg.setColors(ev.evaluate(f, t0, t1) as Int, ev.evaluate(f, b0, b1) as Int)
            }
            start()
        }
    }

    private fun render() {
        val first = lastMode == null
        val changed = first || lastMode != mode
        lastMode = mode

        val c = buildContent()
        val m = mode
        val open = m == Mode.OPEN
        val fixed = open || m == Mode.NOTIF
        c.measure(
            View.MeasureSpec.makeMeasureSpec(
                dp(cfg.openW),
                if (fixed) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tw = c.measuredWidth
        val th = c.measuredHeight

        bigR = if (fixed) dp(cfg.radius).toFloat() else 1000f
        shell.invalidateOutline()
        applyStyle(m, !first && changed)

        enterAnim?.cancel()
        val olds = ArrayList<View>()
        for (i in 0 until shell.childCount) olds.add(shell.getChildAt(i))
        if (changed && !first) {
            for (o in olds) {
                o.animate().cancel()
                o.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(120)
                    .withEndAction { shell.removeView(o) }.start()
            }
        } else {
            for (o in olds) {
                o.animate().cancel()
                shell.removeView(o)
            }
        }
        shell.addView(c, FrameLayout.LayoutParams(tw, th, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        if (first) snap(tw * 0.55f, th * 0.6f)
        tgtW = tw
        tgtH = th
        configureSprings(tw.toLong() * th > (curW * curH).toLong())
        reserveWindow()
        springW.animateToFinalPosition(tw.toFloat())
        springH.animateToFinalPosition(th.toFloat())

        if (changed) enter(c, open, tw)
    }

    private fun enter(c: View, open: Boolean, tw: Int) {
        val stagger = open && c is ViewGroup
        c.pivotX = tw / 2f
        c.pivotY = 0f
        if (stagger) {
            val g = c as ViewGroup
            for (i in 0 until g.childCount) g.getChildAt(i).alpha = 0f
        } else {
            c.alpha = 0f
            c.scaleX = 0.92f
            c.scaleY = 0.92f
        }
        val d = resources.displayMetrics.density
        var lastLvl = -1
        var cancelled = false
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = 340L * 100 / cfg.speed.coerceAtLeast(10)
        va.startDelay = 50
        va.interpolator = DecelerateInterpolator(1.3f)
        va.addUpdateListener { a ->
            val f = a.animatedValue as Float
            if (stagger) {
                val g = c as ViewGroup
                for (i in 0 until g.childCount) {
                    val pi = ((f - 0.1f * i) / 0.65f).coerceIn(0f, 1f)
                    val ch = g.getChildAt(i)
                    ch.alpha = pi
                    ch.translationY = (1f - pi) * -(10f * d)
                }
            } else {
                c.alpha = f
                val s = 0.92f + 0.08f * f
                c.scaleX = s
                c.scaleY = s
            }
            if (Build.VERSION.SDK_INT >= 31 && cfg.focusBlur) {
                val lvl = Math.ceil(((1f - f) * 3f).toDouble()).toInt().coerceIn(0, 3)
                if (lvl != lastLvl) {
                    lastLvl = lvl
                    c.setRenderEffect(fxLevel(lvl))
                }
            }
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(an: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(an: Animator) {
                if (Build.VERSION.SDK_INT >= 31) c.setRenderEffect(null)
                if (cancelled) return
                c.alpha = 1f
                c.scaleX = 1f
                c.scaleY = 1f
                if (c is ViewGroup) {
                    for (i in 0 until c.childCount) {
                        c.getChildAt(i).alpha = 1f
                        c.getChildAt(i).translationY = 0f
                    }
                }
            }
        })
        enterAnim = va
        va.start()
    }

    // ---------- contenus ----------
    private fun buildContent(): View {
        chronoTv = null; idleChronoTv = null; progress = null; posTv = null
        timerViews.clear()
        onTap(null)
        return when (mode) {
            Mode.IDLE -> idleContent()
            Mode.NOTIF -> notifContent()
            Mode.CHARGE -> chargeContent()
            Mode.OPEN -> openContent()
        }
    }

    /** Ligne avec un trou au milieu (caméra) : une vue à gauche, une à droite. */
    private fun splitRow(l: View?, r: View?, minSide: Int): LinearLayout {
        val lw = l?.let { measureW(it) } ?: 0
        val rw = r?.let { measureW(it) } ?: 0
        val side = max(max(lw, rw) + dp(14), minSide)
        val rowH = dp(cfg.idleH)
        val left = FrameLayout(this).apply {
            l?.let {
                addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = dp(8) })
            }
        }
        val right = FrameLayout(this).apply {
            r?.let {
                addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { marginStart = dp(8) })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = rowH
            addView(left, LinearLayout.LayoutParams(side, rowH))
            addView(View(context), LinearLayout.LayoutParams(dp(cfg.holeW), rowH))
            addView(right, LinearLayout.LayoutParams(side, rowH))
        }
    }

    private fun idleContent(): View {
        onTap {
            refreshMedia()
            mode = Mode.OPEN
            render()
        }
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var rightView: View? = null
        val timerStr = if (cfg.chronoOn) timerLabel() else null
        val playing = cfg.musicOn && ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) {
            icons.addView(txt("♪", 15f, true).apply { setTextColor(0xFF1DB954.toInt()) })
        }
        if (cfg.torchOn && lampOn) {
            icons.addView(txt("🔦", 13f).apply { setPadding(dp(6), 0, 0, 0) })
        }
        if (timerStr != null) {
            icons.addView(txt("⏱", 14f).apply { setPadding(dp(6), 0, 0, 0) })
            idleChronoTv = txt(timerStr, 13f, true).apply {
                setTextColor(0xFFFF9F0A.toInt())
                fontFeatureSettings = "tnum"
            }
            rightView = idleChronoTv
        } else if (playing) {
            rightView = txt("▂▅▃", 11f, true).apply { setTextColor(0xFF1DB954.toInt()) }
        }
        if (icons.childCount == 0) {
            return LinearLayout(this).apply {
                minimumWidth = dp(cfg.idleW)
                minimumHeight = dp(cfg.idleH)
            }
        }
        val minSide = ((dp(cfg.idleW) - dp(cfg.holeW)) / 2).coerceAtLeast(dp(20))
        return splitRow(icons, rightView, minSide)
    }

    private fun chargeContent(): View {
        val green = 0xFF30D158.toInt()
        val l = txt("⚡", 17f).apply { setTextColor(green) }
        val r = txt("$chargeLevel%", 15f, true).apply {
            setTextColor(green)
            fontFeatureSettings = "tnum"
        }
        return splitRow(l, r, dp(52))
    }

    private fun notifContent(): View {
        val n = notif ?: run { mode = Mode.IDLE; return idleContent() }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(cfg.idleH))
        }
        head.addView(ImageView(this).apply { setImageDrawable(n.icon) },
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(18) })
        head.addView(
            txt(n.badge ?: "maintenant", 12f, n.badge != null).apply {
                alpha = .8f
                fontFeatureSettings = "tnum"
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(18) }
        )
        col.addView(head)
        col.addView(txt(n.title, 15f, true).apply {
            setPadding(dp(18), 0, dp(18), if (n.text.isBlank()) dp(14) else 0)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (n.text.isNotBlank()) {
            col.addView(txt(n.text, 13f).apply {
                alpha = .8f
                setPadding(dp(18), dp(2), dp(18), dp(14))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }
        onTap {
            try { n.intent?.send() } catch (e: Exception) { }
            close()
        }
        return col
    }

    private fun openContent(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(12))
        }
        // zone du trou de la caméra : on laisse de la place
        v.addView(View(this), LinearLayout.LayoutParams(-1, dp(cfg.idleH)))
        if (cfg.musicOn) ctrl?.let { v.addView(mediaCard(it)) }
        if (cfg.chronoOn) {
            timers.values.forEach { v.addView(timerCard(it)) }
            if (running || acc > 0) v.addView(chronoCard())
        }
        if (cfg.torchOn && lampOn) v.addView(torchCard())
        if (v.childCount == 1) {
            v.addView(txt("Rien en cours", 13f).apply {
                alpha = .6f
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        onTap { close() }
        return v
    }

    private fun card(c1: Int, c2: Int, r: Int = 26) = LinearLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c1, c2))
            .apply { cornerRadius = dp(r).toFloat() }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun mediaCard(c: MediaController): View {
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Lecture"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING

        val card = card(0x12FFFFFF, 0x66E8507F)
        card.orientation = LinearLayout.VERTICAL

        val topRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(ImageView(this).apply {
            setImageBitmap(art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x33FFFFFF)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat())
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        })
        topRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(txt(title, 15f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(txt(artist, 12f).apply {
                alpha = .75f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        card.addView(topRow)

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) }
        }
        progress = pb
        card.addView(pb)
        posTv = txt("", 10f).apply { alpha = .7f }
        card.addView(posTv)

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }
        row.addView(circleBtn("⏮", 0x22FFFFFF, 44) { c.transportControls.skipToPrevious() })
        row.addView(circleBtn(if (playing) "⏸" else "▶", 0x44FFFFFF, 52) {
            if (playing) c.transportControls.pause() else c.transportControls.play()
        })
        row.addView(circleBtn("⏭", 0x22FFFFFF, 44) { c.transportControls.skipToNext() })
        card.addView(row)
        return card
    }

    private fun torchCard(): View {
        val card = card(0x22FFFFFF, 0x0DFFFFFF)
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(txt("🔦", 22f))
        card.addView(txt("Lampe torche", 15f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        })
        card.addView(circleBtn("✕", 0xFFF28B82.toInt(), 44) {
            try { torchId?.let { id -> cm.setTorchMode(id, false) } } catch (e: Exception) { }
        })
        return card
    }

    private fun timerCard(t: TimerInfo): View {
        val card = card(0x22FFFFFF, 0x0DFFFFFF)
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(txt("⏱", 22f))
        val tv = txt(timerText(t), 26f, true).apply { fontFeatureSettings = "tnum" }
        timerViews[t.key] = tv
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(tv)
            addView(txt(t.title.ifBlank { "Minuteur" }, 11f).apply { alpha = .7f })
        })
        card.setOnClickListener {
            try { t.intent?.send() } catch (e: Exception) { }
            close()
        }
        return card
    }

    private fun chronoCard(): View {
        val card = card(0x22FFFFFF, 0x0DFFFFFF)
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(txt("⏱", 22f))
        chronoTv = txt(fmt(chronoMs()), 26f, true).apply { fontFeatureSettings = "tnum" }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(chronoTv)
            addView(txt("Chrono", 11f).apply { alpha = .7f })
        })
        card.addView(circleBtn("↺", 0xFF7E74D9.toInt()) {
            running = false; acc = 0
            render()
        })
        card.addView(circleBtn(if (running) "⏸" else "▶", 0xFFF28B82.toInt()) { b ->
            if (running) { acc = chronoMs(); running = false }
            else { last = SystemClock.elapsedRealtime(); running = true }
            b.text = if (running) "⏸" else "▶"
        })
        return card
    }

    // ---------- service ----------
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("island", "Island", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Island actif")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int) = START_STICKY

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        enterAnim?.cancel()
        colorAnim?.cancel()
        if (::springW.isInitialized) {
            springW.cancel()
            springH.cancel()
        }
        sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        sp.edit().putBoolean("running", false).apply()
        Bus.notif = null
        Bus.connected = null
        Bus.cmd = null
        Bus.preview = null
        Bus.timer = null
        Bus.timerGone = null
        try { cm.unregisterTorchCallback(torchCb) } catch (e: Exception) { }
        try { unregisterReceiver(rec) } catch (e: Exception) { }
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        ctrl?.unregisterCallback(cb)
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
