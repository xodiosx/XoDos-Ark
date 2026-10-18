package app.xodos2.ui.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import java.io.File

/**
 * Background view that renders static images AND animated GIF / WebP / APNG.
 *
 *  • API 28+  → ImageDecoder → AnimatedImageDrawable (GIF, animated WebP, APNG)
 *  • API < 28 → Movie (GIF only; static formats still work via BitmapDrawable)
 *
 * CENTER_CROP scaling is applied so the image fills the terminal area.
 */
class AnimatedBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var drawable: Drawable? = null
    private var movieStartTime: Long = 0L

    fun setSource(path: String?) {
        clear()
        if (path.isNullOrBlank()) return

        val file = File(path)
        if (!file.isFile) return

        // ── 1) ImageDecoder → animated (API 28+) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                val d = ImageDecoder.decodeDrawable(source)
                d.callback = this          // so invalidateSelf() → redraws
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                drawable = d
                invalidate()
                return
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder failed, falling back to Movie/Bitmap", e)
            }
        }

        // ── 2) Movie fallback for GIFs (works on all APIs) ──
        try {
            val m = Movie.decodeFile(path)
            if (m != null) {
                movie = m
                movieStartTime = SystemClock.uptimeMillis()
                invalidate()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movie decode failed", e)
        }

        // ── 3) Last resort: static first frame ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            drawable = BitmapDrawable(resources, bmp).also { it.callback = this }
            invalidate()
        }
    }

    fun clear() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable = null
        movie = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawable?.let { d ->
            drawCenterCrop(canvas, d)
            // AnimatedImageDrawable: keep redrawing next frame
            if (d is AnimatedImageDrawable && d.isRunning) {
                postInvalidateOnAnimation()
            }
            return
        }

        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            val scale = maxOf(
                width.toFloat() / m.width(),
                height.toFloat() / m.height()
            )
            val dx = (width - m.width() * scale) / 2f
            val dy = (height - m.height() * scale) / 2f

            canvas.save()
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            m.draw(canvas, 0f, 0f)
            canvas.restore()

            postInvalidateOnAnimation()
        }
    }

    private fun drawCenterCrop(canvas: Canvas, d: Drawable) {
        val iw = d.intrinsicWidth
        val ih = d.intrinsicHeight

        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, width, height)
            d.draw(canvas)
            return
        }

        val scale = maxOf(width.toFloat() / iw, height.toFloat() / ih)
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        val dx = (width - dw) / 2
        val dy = (height - dh) / 2
        d.setBounds(dx, dy, dx + dw, dy + dh)
        d.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? AnimatedImageDrawable)?.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    private companion object {
        const val TAG = "BgView"
    }
}