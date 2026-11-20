package com.evan.island

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

class GlassDrawable(
    top: Int,
    bottom: Int,
    private val glass: Boolean,
    private val border: Boolean,
    private val density: Float
) : Drawable() {

    var radius = 0f
        set(v) { field = v; invalidateSelf() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val m = Matrix()
    private val base = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
    private val sheen = LinearGradient(
        0f, 0f, 0f, 1f, 0x3CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
    )

    override fun draw(c: Canvas) {
        r.set(bounds)
        val w = r.width()
        val hh = r.height()
        if (w <= 0f || hh <= 0f) return
        val rad = min(radius, min(w, hh) / 2f)

        p.style = Paint.Style.FILL
        m.setScale(1f, hh)
        m.postTranslate(0f, r.top)
        base.setLocalMatrix(m)
        p.shader = base
        c.drawRoundRect(r, rad, rad, p)

        if (glass) {
            m.setScale(1f, hh * 0.6f)
            m.postTranslate(0f, r.top)
            sheen.setLocalMatrix(m)
            p.shader = sheen
            c.drawRoundRect(r, rad, rad, p)
        }
        if (border) {
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = density
            p.color = 0x55FFFFFF
            val hf = density / 2f
            r.inset(hf, hf)
            c.drawRoundRect(r, rad - hf, rad - hf, p)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
