package com.evan.island

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.BLACK)
        }
        fun btn(t: String, f: () -> Unit) {
            col.addView(Button(this).apply {
                text = t
                setOnClickListener { f() }
            })
        }
        btn("1. Autoriser l'affichage par-dessus") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
        }
        btn("2. Démarrer l'île") {
            if (Settings.canDrawOverlays(this)) {
                startForegroundService(Intent(this, IslandService::class.java))
            }
        }
        btn("Arrêter") { stopService(Intent(this, IslandService::class.java)) }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContentView(col)
    }
}
