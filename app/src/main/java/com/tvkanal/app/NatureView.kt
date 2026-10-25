package com.tvkanal.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Radyo ekranının arkasında akan canlı doğa manzarası.
 * Tamamen kodla çizilir: dağlar, göl, kayan bulutlar, ışıldayan su, uçan kuşlar.
 * Gökyüzü cihazın saatine göre sabah / gündüz / akşam / gece renklerine geçer.
 */
class NatureView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var t = 0f // saniye
    // Android 5-6 onVisibilityChanged'i bu alan hazırlanmadan çağırır; bu yüzden boş olabilir
    private val animator: ValueAnimator? = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t += 1f / 30f; invalidate() }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rnd = Random(7)
    private val clouds = List(7) { floatArrayOf(rnd.nextFloat(), 0.08f + rnd.nextFloat() * 0.25f, 0.6f + rnd.nextFloat() * 0.9f, 4f + rnd.nextFloat() * 6f) }
    private val stars = List(90) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.5f, rnd.nextFloat() * 6f) }
    private val birds = List(4) { floatArrayOf(rnd.nextFloat(), 0.15f + rnd.nextFloat() * 0.2f, 20f + rnd.nextFloat() * 25f) }

    /** Gün içi evre: 0 gece, 1 gündüz; ara değerler şafak/akşam. */
    private fun dayLight(): Float {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
        return when {
            h < 5f || h >= 21f -> 0f
            h < 8f -> (h - 5f) / 3f
            h < 18f -> 1f
            else -> 1f - (h - 18f) / 3f
        }
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator?.start() }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) animator?.start() else animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val day = dayLight()
        val dusk = (1f - kotlin.math.abs(day - 0.5f) * 2f).coerceIn(0f, 1f) // şafak/akşam ağırlığı
        val horizon = h * 0.62f

        // Gökyüzü
        val skyTop = mix(mix(Color.rgb(8, 12, 32), Color.rgb(40, 110, 200), day), Color.rgb(60, 50, 120), dusk * 0.6f)
        val skyBot = mix(mix(Color.rgb(25, 30, 60), Color.rgb(170, 210, 240), day), Color.rgb(250, 140, 90), dusk)
        paint.shader = LinearGradient(0f, 0f, 0f, horizon, skyTop, skyBot, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, horizon, paint)
        paint.shader = null

        // Yıldızlar (gece)
        if (day < 0.6f) {
            for (s in stars) {
                val a = ((1f - day / 0.6f) * (0.5f + 0.5f * sin(t * 1.5f + s[2]))).coerceIn(0f, 1f)
                paint.color = Color.argb((a * 220).toInt(), 255, 255, 255)
                canvas.drawCircle(s[0] * w, s[1] * horizon, 1.6f, paint)
            }
        }

        // Güneş / ay (yavaşça süzülür)
        val sx = w * (0.72f + 0.02f * sin(t / 40f)); val sy = horizon * (0.62f - 0.35f * day)
        val sunCol = if (day > 0.15f) mix(Color.rgb(255, 170, 90), Color.rgb(255, 245, 210), day) else Color.rgb(230, 235, 255)
        paint.shader = RadialGradient(sx, sy, h * 0.18f, Color.argb(120, Color.red(sunCol), Color.green(sunCol), Color.blue(sunCol)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(sx, sy, h * 0.18f, paint)
        paint.shader = null
        paint.color = sunCol
        canvas.drawCircle(sx, sy, h * 0.045f, paint)

        // Bulutlar (sola doğru kayar)
        for (c in clouds) {
            val cx = ((c[0] * (w + 600f) - t * c[3] * 6f) % (w + 600f) + (w + 600f)) % (w + 600f) - 300f
            val cy = c[1] * horizon; val s = c[2] * h * 0.06f
            paint.color = Color.argb((22 + 80 * day).toInt(), 255, 255, 255)
            canvas.drawOval(cx - s * 2.2f, cy - s * 0.5f, cx + s * 2.2f, cy + s * 0.6f, paint)
            canvas.drawCircle(cx - s * 0.7f, cy - s * 0.3f, s * 0.75f, paint)
            canvas.drawCircle(cx + s * 0.5f, cy - s * 0.45f, s * 0.95f, paint)
        }

        // Dağ katmanları (arka açık, ön koyu)
        val layers = listOf(
            Triple(0.30f, 0.0021f, mix(Color.rgb(28, 32, 60), Color.rgb(120, 150, 185), day)),
            Triple(0.21f, 0.0034f, mix(Color.rgb(18, 22, 42), Color.rgb(70, 110, 120), day)),
            Triple(0.12f, 0.0052f, mix(Color.rgb(8, 12, 22), Color.rgb(35, 75, 60), day)),
        )
        layers.forEachIndexed { i, (amp, freq, col) ->
            path.reset(); path.moveTo(0f, horizon)
            var x = 0f
            while (x <= w) {
                val y = horizon - h * amp * (0.55f + 0.45f * sin(x * freq + i * 1.7f) * sin(x * freq * 0.37f + i))
                path.lineTo(x, y); x += 8f
            }
            path.lineTo(w, horizon); path.close()
            paint.color = col
            canvas.drawPath(path, paint)
        }

        // Göl
        val waterTop = mix(Color.rgb(15, 22, 45), Color.rgb(70, 130, 170), day)
        val waterBot = mix(Color.rgb(5, 8, 18), Color.rgb(20, 60, 90), day)
        paint.shader = LinearGradient(0f, horizon, 0f, h, waterTop, waterBot, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, horizon, w, h, paint)
        paint.shader = null

        // Suda güneş/ay yansıması ve dalga ışıltıları
        paint.strokeWidth = 3f
        for (i in 0 until 26) {
            val yy = horizon + 10f + i * (h - horizon) / 26f
            val wob = sin(t * 1.3f + i * 0.9f) * 18f
            val len = (h * 0.09f) * (1f - i / 34f)
            paint.color = Color.argb((140 - i * 4).coerceAtLeast(20), Color.red(sunCol), Color.green(sunCol), Color.blue(sunCol))
            canvas.drawLine(sx - len + wob, yy, sx + len + wob, yy, paint)
        }
        for (i in 0 until 40) {
            val px = ((i * 197.3f + t * 12f) % w)
            val py = horizon + 20f + ((i * 61.7f) % (h - horizon - 30f))
            val a = (0.5f + 0.5f * sin(t * 2f + i)).coerceIn(0f, 1f)
            paint.color = Color.argb((a * 70).toInt(), 255, 255, 255)
            canvas.drawLine(px, py, px + 22f, py, paint)
        }

        // Kuşlar (gündüz)
        if (day > 0.3f) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            paint.color = Color.argb((day * 160).toInt(), 30, 30, 40)
            for (b in birds) {
                val bx = ((b[0] * (w + 400f) + t * b[2]) % (w + 400f)) - 200f
                val by = b[1] * horizon + sin(t + b[0] * 10f) * 8f
                val flap = sin(t * 6f + b[0] * 20f) * 6f
                path.reset()
                path.moveTo(bx - 14f, by - flap); path.quadTo(bx - 6f, by - 8f, bx, by)
                path.quadTo(bx + 6f, by - 8f, bx + 14f, by - flap)
                canvas.drawPath(path, paint)
            }
            paint.style = Paint.Style.FILL
        }

        // Yazının okunması için hafif karartma
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    @Suppress("unused") private val twoPi = (2 * PI).toFloat()
}
