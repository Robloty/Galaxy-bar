package com.evan.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    private val h = Handler(Looper.getMainLooper())

    private var mode = Mode.IDLE
    private var lastMode: Mode? = null
    private var gen = 0
    private var anim: ValueAnimator? = null
    private var bg: GlassDrawable? = null
    private var fx: Array<RenderEffect?>? = null

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
        if (Build.VERSION.SDK_INT >= 31) {
            if (cfg.blur > 0 && wm.isCrossWindowBlurEnabled) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = (cfg.blur * 0.6f * resources.displayMetrics.density).toInt()
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                lp.blurBehindRadius = 0
            }
        }
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    private fun setWindow(w: Int, hh: Int) {
        if (lp.width == w && lp.height == hh) return
        lp.width = w
        lp.height = hh
        if (root.isAttachedToWindow) wm.updateViewLayout(root, lp)
    }

    // ---------- boucle temps réel ----------
    private val tick = object : Runnable {
        override fun run() {
            chronoTv?.text = fmt(chronoMs())
            idleChronoTv?.text = "⏱ " + (timerLabel() ?: "")
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
                flash(Mode.CHARGE, 2500)
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

        shell = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        }
        root = FrameLayout(this).apply {
            addView(shell)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE && mode == Mode.OPEN) {
                    mode = Mode.IDLE; render()
                }
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
                        t.title.ifBlank { "Minuteur" }, timerText(t), t.icon, t.intent
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
                    "charge" -> { chargeLevel = batteryPct(); flash(Mode.CHARGE, 2500) }
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

    // ---------- rendu + animation ----------
    private fun render() {
        val first = lastMode == null
        val animate = first || lastMode != mode
        lastMode = mode
        gen++
        val g = gen
        anim?.cancel()
        val old = if (shell.childCount > 0) shell.getChildAt(0) else null
        if (animate && !first && old != null) {
            old.animate().alpha(0f)
                .setDuration(110L * 100 / cfg.speed.coerceAtLeast(10))
                .withEndAction { if (g == gen) morph(true, false) }
                .start()
        } else {
            old?.animate()?.cancel()
            morph(animate, first)
        }
    }

    private fun setShell(w: Int, hh: Int) {
        val p = shell.layoutParams as FrameLayout.LayoutParams
        p.width = w
        p.height = hh
        shell.layoutParams = p
    }

    private fun morph(animate: Boolean, first: Boolean) {
        val c = buildContent()
        val m = mode
        val open = m == Mode.OPEN
        val fixed = open || m == Mode.NOTIF
        val solid = m == Mode.IDLE || m == Mode.CHARGE
        c.measure(
            View.MeasureSpec.makeMeasureSpec(
                dp(cfg.openW),
                if (fixed) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val tw = c.measuredWidth
        val th = c.measuredHeight
        val raw = if (fixed) dp(cfg.radius).toFloat() else th / 2f
        val toR = min(raw, th / 2f)

        val tint = TINTS[cfg.tint.coerceIn(0, TINTS.lastIndex)]
        val top = when {
            open -> tint.top
            solid -> 0xFF000000.toInt()
            else -> 0xFF1A1A22.toInt()
        }
        val bottom = if (open) tint.bottom else 0xFF000000.toInt()
        val pct = when {
            solid || !cfg.glass -> 100
            open -> cfg.opacity
            else -> (cfg.opacity + 10).coerceAtMost(100)
        }
        val newBg = GlassDrawable(
            withAlpha(top, pct), withAlpha(bottom, pct),
            cfg.glass && !solid, cfg.border && !solid,
            resources.displayMetrics.density
        )

        val cur = shell.layoutParams
        val fromW = if (first || cur.width <= 0) (tw * 0.5f).toInt() else cur.width
        val fromH = if (first || cur.height <= 0) (th * 0.6f).toInt() else cur.height
        val fromR = if (first) toR else (bg?.radius ?: toR)

        bg = newBg
        shell.background = newBg
        shell.removeAllViews()
        shell.addView(c, FrameLayout.LayoutParams(tw, th, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        if (!animate) {
            setShell(tw, th)
            newBg.radius = toR
            setWindow(tw, th)
            return
        }

        val margin = if (cfg.bounce > 0) dp(14) else 0
        setWindow(max(fromW, tw) + margin, max(fromH, th) + margin)
        newBg.radius = fromR
        setShell(fromW, fromH)

        val dur = 440L * 100 / cfg.speed.coerceAtLeast(10)
        val ip = OvershootInterpolator(cfg.bounce / 100f * 3f)
        val d = resources.displayMetrics.density
        c.alpha = if (open) 1f else 0f
        if (open && c is ViewGroup) {
            for (i in 0 until c.childCount) c.getChildAt(i).alpha = 0f
        }

        var lastLvl = -1
        var cancelled = false
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = dur
        va.interpolator = LinearInterpolator()
        va.addUpdateListener { a ->
            val f = a.animatedValue as Float
            val e = ip.getInterpolation(f)
            setShell(
                (fromW + (tw - fromW) * e).toInt().coerceAtLeast(1),
                (fromH + (th - fromH) * e).toInt().coerceAtLeast(1)
            )
            newBg.radius = fromR + (toR - fromR) * f
            val p = ((f - 0.25f) / 0.55f).coerceIn(0f, 1f)
            if (open && c is ViewGroup) {
                for (i in 0 until c.childCount) {
                    val pi = ((f - 0.22f - 0.07f * i) / 0.5f).coerceIn(0f, 1f)
                    val ch = c.getChildAt(i)
                    ch.alpha = pi
                    ch.translationY = (1f - pi) * -(10f * d)
                }
            } else {
                c.alpha = p
            }
            if (Build.VERSION.SDK_INT >= 31 && cfg.focusBlur) {
                val lvl = Math.ceil(((1f - p) * 4f).toDouble()).toInt().coerceIn(0, 4)
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
                c.alpha = 1f
                if (c is ViewGroup) {
                    for (i in 0 until c.childCount) {
                        c.getChildAt(i).alpha = 1f
                        c.getChildAt(i).translationY = 0f
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) c.setRenderEffect(null)
                if (!cancelled) setWindow(tw, th)
            }
        })
        anim = va
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

    private fun idleContent(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(cfg.idleW)
            minimumHeight = dp(cfg.idleH)
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        if (cfg.musicOn && ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            v.addView(txt("♪", 15f, true).apply { setTextColor(0xFF1DB954.toInt()) })
        }
        if (cfg.torchOn && lampOn) {
            v.addView(txt("🔦", 13f).apply { setPadding(dp(8), 0, 0, 0) })
        }
        if (cfg.chronoOn) {
            timerLabel()?.let {
                idleChronoTv = txt("⏱ $it", 12f, true).apply { setPadding(dp(8), 0, 0, 0) }
                v.addView(idleChronoTv)
            }
        }
        onTap {
            refreshMedia()
            mode = Mode.OPEN
            render()
        }
        return v
    }

    private fun notifContent(): View {
        val n = notif ?: run { mode = Mode.IDLE; return idleContent() }
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
        }
        v.addView(ImageView(this).apply {
            setImageDrawable(n.icon)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        v.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(txt(n.title, 14f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(txt(n.text, 12f).apply {
                alpha = .75f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        onTap {
            try { n.intent?.send() } catch (e: Exception) { }
            mode = Mode.IDLE
            render()
        }
        return v
    }

    private fun chargeContent(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(150)
            setPadding(dp(18), dp(9), dp(18), dp(9))
        }
        v.addView(txt("⚡", 16f).apply { setTextColor(0xFF4ADE80.toInt()) })
        v.addView(txt("$chargeLevel%", 15f, true).apply { setPadding(dp(8), 0, 0, 0) })
        return v
    }

    private fun openContent(): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(12))
        }
        v.addView(View(this).apply {
            background = rounded(0x55FFFFFF, dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4))
        })
        if (cfg.musicOn) ctrl?.let { v.addView(mediaCard(it)) }
        if (cfg.torchOn && lampOn) v.addView(torchCard())
        if (cfg.chronoOn) {
            timers.values.forEach { v.addView(timerCard(it)) }
            if (running || acc > 0) v.addView(chronoCard())
        }
        if (v.childCount == 1) {
            v.addView(txt("Rien en cours", 13f).apply {
                alpha = .6f; setPadding(0, dp(14), 0, dp(6))
            })
        }
        onTap { mode = Mode.IDLE; render() }
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
        val tv = txt(timerText(t), 26f, true)
        timerViews[t.key] = tv
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(tv)
            addView(txt(t.title.ifBlank { "Minuteur" }, 11f).apply { alpha = .7f })
        })
        card.setOnClickListener {
            try { t.intent?.send() } catch (e: Exception) { }
            mode = Mode.IDLE
            render()
        }
        return card
    }

    private fun chronoCard(): View {
        val card = card(0x22FFFFFF, 0x0DFFFFFF)
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(txt("⏱", 22f))
        chronoTv = txt(fmt(chronoMs()), 26f, true)
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
        anim?.cancel()
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
