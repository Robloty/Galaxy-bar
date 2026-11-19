package com.evan.island

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

class GlassDrawable(
    private val top: Int,
    private val bottom: Int,
    private val glass: Boolean,
    private val border: Boolean,
    private val density: Float
) : Drawable() {

    var radius = 0f
        set(v) { field = v; invalidateSelf() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun draw(c: Canvas) {
        r.set(bounds)
        if (r.width() <= 0f || r.height() <= 0f) return
        val rad = min(radius, min(r.width(), r.height()) / 2f)

        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, p)

        if (glass) {
            p.shader = LinearGradient(
                0f, r.top, 0f, r.top + r.height() * 0.6f,
                0x3CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
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
