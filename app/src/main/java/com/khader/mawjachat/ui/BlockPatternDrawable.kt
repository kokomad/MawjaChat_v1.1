package com.khader.mawjachat.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.random.Random

/** خلفية داكنة من مكعبات متفاوتة الظل مع تعتيم على الأطراف، كما في التصميم. */
class BlockPatternDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.shader = null
        paint.color = Color.rgb(10, 12, 15)
        canvas.drawRect(b, paint)

        val rnd = Random(7)
        val cell = 54f
        var y = b.top.toFloat()
        while (y < b.bottom) {
            var x = b.left.toFloat()
            while (x < b.right) {
                val shade = 16 + rnd.nextInt(20)
                paint.color = Color.rgb(shade, shade + 2, shade + 5)
                canvas.drawRect(x + 2f, y + 2f, x + cell - 2f, y + cell - 2f, paint)
                x += cell
            }
            y += cell
        }

        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val r = maxOf(b.width(), b.height()) * 0.75f
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0x00000000, 0xD9000000.toInt()),
            floatArrayOf(0.25f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(b, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
