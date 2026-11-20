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
    var sheen: Boolean,
    var border: Boolean,
    private val density: Float
) : Drawable() {

    var top = top
        private set
    var bottom = bottom
        private set

    var radius = 0f
        set(v) { field = v; invalidateSelf() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val m = Matrix()
    private var base = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)
    private val hi = LinearGradient(
        0f, 0f, 0f, 1f, 0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
    )

    fun setColors(t: Int, b: Int) {
        top = t
        bottom = b
        base = LinearGradient(0f, 0f, 0f, 1f, t, b, Shader.TileMode.CLAMP)
        invalidateSelf()
    }

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

        if (sheen) {
            m.setScale(1f, hh * 0.55f)
            m.postTranslate(0f, r.top)
            hi.setLocalMatrix(m)
            p.shader = hi
            c.drawRoundRect(r, rad, rad, p)
        }
        if (border) {
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = density
            p.color = 0x38FFFFFF
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
