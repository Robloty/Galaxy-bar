package com.evan.island

import android.content.Context
import android.content.SharedPreferences

data class Tint(val name: String, val top: Int, val bottom: Int)

val TINTS = listOf(
    Tint("Indigo", 0xFF3B2CB0.toInt(), 0xFF12093F.toInt()),
    Tint("Violet", 0xFF6D28D9.toInt(), 0xFF1E0A4A.toInt()),
    Tint("Rose", 0xFFC2417F.toInt(), 0xFF3A0A30.toInt()),
    Tint("Bleu", 0xFF1D6BE0.toInt(), 0xFF0A1A4A.toInt()),
    Tint("Vert", 0xFF16A34A.toInt(), 0xFF052E16.toInt()),
    Tint("Noir", 0xFF2C2C32.toInt(), 0xFF000000.toInt())
)

object Prefs {
    fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("island_prefs", Context.MODE_PRIVATE)
}

data class Cfg(
    val posY: Int = 8,
    val posX: Int = 0,
    val idleW: Int = 130,
    val idleH: Int = 34,
    val openW: Int = 344,
    val radius: Int = 34,
    val glass: Boolean = true,
    val opacity: Int = 75,
    val tint: Int = 0,
    val border: Boolean = true,
    val blur: Int = 0,
    val speed: Int = 100,
    val bounce: Int = 45,
    val focusBlur: Boolean = true,
    val notifOn: Boolean = true,
    val notifSec: Int = 4,
    val chargeOn: Boolean = true,
    val musicOn: Boolean = true,
    val torchOn: Boolean = true,
    val chronoOn: Boolean = true,
    val boot: Boolean = false
) {
    fun save(sp: SharedPreferences) {
        sp.edit()
            .putInt("posY", posY).putInt("posX", posX)
            .putInt("idleW", idleW).putInt("idleH", idleH)
            .putInt("openW", openW).putInt("radius", radius)
            .putBoolean("glass", glass).putInt("opacity", opacity)
            .putInt("tint", tint).putBoolean("border", border)
            .putInt("blur", blur).putInt("speed", speed)
            .putInt("bounce", bounce).putBoolean("focusBlur", focusBlur)
            .putBoolean("notifOn", notifOn).putInt("notifSec", notifSec)
            .putBoolean("chargeOn", chargeOn).putBoolean("musicOn", musicOn)
            .putBoolean("torchOn", torchOn).putBoolean("chronoOn", chronoOn)
            .putBoolean("boot", boot)
            .apply()
    }

    companion object {
        fun load(sp: SharedPreferences): Cfg {
            val d = Cfg()
            return Cfg(
                posY = sp.getInt("posY", d.posY),
                posX = sp.getInt("posX", d.posX),
                idleW = sp.getInt("idleW", d.idleW),
                idleH = sp.getInt("idleH", d.idleH),
                openW = sp.getInt("openW", d.openW),
                radius = sp.getInt("radius", d.radius),
                glass = sp.getBoolean("glass", d.glass),
                opacity = sp.getInt("opacity", d.opacity),
                tint = sp.getInt("tint", d.tint),
                border = sp.getBoolean("border", d.border),
                blur = sp.getInt("blur", d.blur),
                speed = sp.getInt("speed", d.speed),
                bounce = sp.getInt("bounce", d.bounce),
                focusBlur = sp.getBoolean("focusBlur", d.focusBlur),
                notifOn = sp.getBoolean("notifOn", d.notifOn),
                notifSec = sp.getInt("notifSec", d.notifSec),
                chargeOn = sp.getBoolean("chargeOn", d.chargeOn),
                musicOn = sp.getBoolean("musicOn", d.musicOn),
                torchOn = sp.getBoolean("torchOn", d.torchOn),
                chronoOn = sp.getBoolean("chronoOn", d.chronoOn),
                boot = sp.getBoolean("boot", d.boot)
            )
        }
    }
}
