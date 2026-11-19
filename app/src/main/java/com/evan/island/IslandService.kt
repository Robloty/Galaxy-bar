package com.evan.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
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
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat

class IslandService : Service() {
    private enum class Mode { IDLE, NOTIF, OPEN, CHARGE }

    private val TOP = 8 // distance du haut en dp : ajuste pour coller à ta caméra

    private lateinit var wm: WindowManager
    private lateinit var msm: MediaSessionManager
    private lateinit var cm: CameraManager
    private lateinit var root: FrameLayout
    private lateinit var island: LinearLayout
    private val h = Handler(Looper.getMainLooper())

    private var mode = Mode.IDLE
    private var notif: NotifData? = null
    private var chargeLevel = 0
    private var ctrl: MediaController? = null
    private var torchId: String? = null
    private var torchOn = false
    private var running = false
    private var acc = 0L
    private var last = 0L
    private var hooked = false

    private var chronoTv: TextView? = null
    private var idleChronoTv: TextView? = null
    private var progress: ProgressBar? = null
    private var posTv: TextView? = null

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.toFloat()
    }

    private fun gradient(a: Int, b: Int, r: Int) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b))
            .apply { cornerRadius = r.toFloat() }

    private fun txt(s: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
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

    private fun mediaPos(): Long {
        val s = ctrl?.playbackState ?: return 0
        if (s.state == PlaybackState.STATE_PLAYING) {
            val d = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            return (s.position + d * s.playbackSpeed).toLong()
        }
        return s.position
    }

    private fun setW(w: Int) {
        island.layoutParams =
            FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
    }

    // ---------- boucle temps réel ----------
    private val tick = object : Runnable {
        override fun run() {
            val t = fmt(chronoMs())
            chronoTv?.text = t
            idleChronoTv?.text = t
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
            torchOn = enabled
            refreshIfCalm()
        }
    }

    // ---------- charge ----------
    private val rec = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_POWER_CONNECTED) {
                val b = this@IslandService.registerReceiver(
                    null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                chargeLevel = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
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
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        msm = getSystemService(MediaSessionManager::class.java)
        cm = getSystemService(CameraManager::class.java)

        island = LinearLayout(this)
        root = FrameLayout(this).apply {
            addView(island)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE && mode == Mode.OPEN) {
                    mode = Mode.IDLE; render()
                }
                false
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(TOP)
        }
        wm.addView(root, lp)

        Bus.notif = { n -> notif = n; flash(Mode.NOTIF, 4500) }
        Bus.connected = { hookMedia() }
        cm.registerTorchCallback(torchCb, h)
        ContextCompat.registerReceiver(
            this, rec, IntentFilter(Intent.ACTION_POWER_CONNECTED),
            ContextCompat.RECEIVER_EXPORTED
        )
        hookMedia()
        render()
        h.post(tick)
    }

    // ---------- rendu ----------
    private fun render() {
        TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(220))
        island.removeAllViews()
        island.setOnClickListener(null)
        island.isClickable = false
        island.minimumWidth = 0
        island.minimumHeight = 0
        chronoTv = null; idleChronoTv = null; progress = null; posTv = null
        when (mode) {
            Mode.IDLE -> idleView()
            Mode.NOTIF -> notifView()
            Mode.CHARGE -> chargeView()
            Mode.OPEN -> openView()
        }
    }

    private fun idleView() {
        setW(-2)
        island.orientation = LinearLayout.HORIZONTAL
        island.gravity = Gravity.CENTER_VERTICAL
        island.minimumWidth = dp(120)
        island.minimumHeight = dp(34)
        island.setPadding(dp(12), dp(6), dp(12), dp(6))
        island.background = rounded(Color.BLACK, dp(20))
        if (ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            island.addView(txt("♪", 15f, true).apply { setTextColor(0xFF1DB954.toInt()) })
        }
        if (torchOn) {
            island.addView(txt("🔦", 13f).apply { setPadding(dp(8), 0, 0, 0) })
        }
        if (running || acc > 0) {
            idleChronoTv = txt(fmt(chronoMs()), 12f, true).apply { setPadding(dp(8), 0, 0, 0) }
            island.addView(idleChronoTv)
        }
        island.setOnClickListener {
            refreshMedia()
            mode = Mode.OPEN
            render()
        }
    }

    private fun notifView() {
        val n = notif ?: run { mode = Mode.IDLE; return idleView() }
        setW(dp(340))
        island.orientation = LinearLayout.HORIZONTAL
        island.gravity = Gravity.CENTER_VERTICAL
        island.setPadding(dp(14), dp(10), dp(16), dp(10))
        island.background = rounded(Color.BLACK, dp(32))
        island.addView(ImageView(this).apply {
            setImageDrawable(n.icon)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        island.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(txt(n.title, 14f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(txt(n.text, 12f).apply {
                alpha = .75f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        island.setOnClickListener {
            try { n.intent?.send() } catch (e: Exception) { }
            mode = Mode.IDLE
            render()
        }
    }

    private fun chargeView() {
        setW(-2)
        island.orientation = LinearLayout.HORIZONTAL
        island.gravity = Gravity.CENTER
        island.minimumWidth = dp(160)
        island.setPadding(dp(18), dp(9), dp(18), dp(9))
        island.background = rounded(Color.BLACK, dp(24))
        island.addView(txt("⚡", 16f).apply { setTextColor(0xFF4ADE80.toInt()) })
        island.addView(txt("$chargeLevel%", 15f, true).apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun openView() {
        setW(dp(344))
        island.orientation = LinearLayout.VERTICAL
        island.gravity = Gravity.CENTER_HORIZONTAL
        island.setPadding(dp(8), dp(10), dp(8), dp(10))
        island.background = rounded(Color.BLACK, dp(36))
        island.addView(View(this).apply {
            background = rounded(0x55FFFFFF, dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4))
            setOnClickListener { mode = Mode.IDLE; render() }
        })
        ctrl?.let { island.addView(mediaCard(it)) }
        if (torchOn) island.addView(torchCard())
        island.addView(chronoCard())
    }

    private fun card(c1: Int, c2: Int, r: Int = 28) = LinearLayout(this).apply {
        background = gradient(c1, c2, dp(r))
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

        val card = card(0xFF1A0B5C.toInt(), 0xFF9B2E7A.toInt())
        card.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
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
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(txt(title, 15f, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(txt(artist, 12f).apply {
                alpha = .75f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        })
        card.addView(top)

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
        val soft = 0x22FFFFFF
        row.addView(circleBtn("⏮", soft, 44) { c.transportControls.skipToPrevious() })
        row.addView(circleBtn(if (playing) "⏸" else "▶", 0x44FFFFFF, 52) {
            if (playing) c.transportControls.pause() else c.transportControls.play()
        })
        row.addView(circleBtn("⏭", soft, 44) { c.transportControls.skipToNext() })
        card.addView(row)
        return card
    }

    private fun torchCard(): View {
        val card = card(0xFF2B2B2B.toInt(), 0xFF4A4A4A.toInt(), 26)
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

    private fun chronoCard(): View {
        val card = card(0xFF1A0B5C.toInt(), 0xFF3A0CA3.toInt())
        card.gravity = Gravity.CENTER_VERTICAL
        card.addView(txt("⏱", 22f))
        chronoTv = txt(fmt(chronoMs()), 26f, true)
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            addView(chronoTv)
            addView(txt("Chronomètre", 11f).apply { alpha = .7f })
        })
        card.addView(circleBtn("↺", 0xFF7E74D9.toInt()) {
            acc = 0; last = SystemClock.elapsedRealtime()
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
        Bus.notif = null
        Bus.connected = null
        try { cm.unregisterTorchCallback(torchCb) } catch (e: Exception) { }
        try { unregisterReceiver(rec) } catch (e: Exception) { }
        try { msm.removeOnActiveSessionsChangedListener(sessionsListener) } catch (e: Exception) { }
        ctrl?.unregisterCallback(cb)
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
