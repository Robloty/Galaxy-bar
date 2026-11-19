package com.evan.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class IslandService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var cards: LinearLayout
    private lateinit var pillTime: TextView
    private lateinit var cardTime: TextView
    private val h = Handler(Looper.getMainLooper())
    private var acc = 0L
    private var last = SystemClock.elapsedRealtime()
    private var running = true
    private var expanded = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(c: Int, r: Int) = GradientDrawable().apply {
        setColor(c); cornerRadius = r.toFloat()
    }

    private fun gradient(a: Int, b: Int, r: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(a, b))
            .apply { cornerRadius = r.toFloat() }

    private fun txt(s: String, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun circleBtn(label: String, color: Int, onClick: (TextView) -> Unit) =
        txt(label, 20f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick(this) }
        }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (running) acc += now - last
            last = now
            val s = acc / 1000
            val t = "%d:%02d".format(s / 60, s % 60)
            pillTime.text = t
            cardTime.text = t
            h.postDelayed(this, 100)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildUi()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(10)
        }
        wm.addView(root, lp)
        h.post(tick)
    }

    private fun buildUi() {
        pillTime = txt("0:00", 14f, true)
        cardTime = txt("0:00", 26f, true)

        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF4D4D"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = rounded(Color.BLACK, dp(22))
            addView(dot); addView(pillTime)
            setOnClickListener { toggle() }
        }

        // Carte chrono
        val pause = circleBtn("⏸", Color.parseColor("#F28B82")) { b ->
            running = !running
            b.text = if (running) "⏸" else "▶"
        }
        val reset = circleBtn("↺", Color.parseColor("#7E74D9")) { acc = 0 }
        val chronoLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(12)
            }
            addView(cardTime)
            addView(txt("Chronomètre", 11f).apply { alpha = .7f })
        }
        val chronoCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            background = gradient(Color.parseColor("#1A0B5C"), Color.parseColor("#3A0CA3"), dp(28))
            addView(txt("⏱", 22f)); addView(chronoLabel); addView(reset); addView(pause)
        }

        // Capsule alarme (démo)
        val alarmCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(18), dp(10))
            background = gradient(Color.parseColor("#2A10B0"), Color.parseColor("#3D1FD6"), dp(40))
            addView(txt("⏰", 22f).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.parseColor("#55FFFFFF"), dp(30))
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            })
            addView(txt("Start", 16f, true).apply {
                setPadding(dp(14), 0, 0, 0)
            })
        }

        cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(340), -2).apply { topMargin = dp(8) }
            chronoCard.layoutParams = LinearLayout.LayoutParams(-1, -2)
            alarmCard.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            addView(chronoCard); addView(alarmCard)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill); addView(cards)
        }
    }

    private fun toggle() {
        TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(250))
        expanded = !expanded
        cards.visibility = if (expanded) View.VISIBLE else View.GONE
    }

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
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
