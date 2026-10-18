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
 * Scaling behaviour: **STRETCH** — the image is scaled to fill the entire view
 * exactly (aspect ratio is NOT preserved, no cropping, no letterboxing).
 * This matches the "force stretch full screen" requirement.
 *
 *  • API 28+  → ImageDecoder → AnimatedImageDrawable (GIF, animated WebP, APNG)
 *  • API < 28 → Movie (GIF only; static formats still work via BitmapDrawable)
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
        if (!file.isFile) {
            Log.w(TAG, "setSource: not a file — $path")
            return
        }

        // ── 1) ImageDecoder → supports animated GIF/WebP/APNG on API 28+ ──
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
                Log.i(TAG, "ImageDecoder: ${d.javaClass.simpleName} for $path")
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
                Log.i(TAG, "Movie: ${m.width()}x${m.height()} for $path")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movie decode failed", e)
        }

        // ── 3) Last resort: static first frame ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            drawable = BitmapDrawable(resources, bmp).also { it.callback = this }
            invalidate()
            Log.i(TAG, "Bitmap fallback: ${bmp.width}x${bmp.height} for $path")
        } ?: Log.e(TAG, "Could not decode $path with any method")
    }

    fun clear() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable = null
        movie = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── Drawable path (static + AnimatedImageDrawable) ──
        drawable?.let { d ->
            // STRETCH to fill the whole view — no cropping, no aspect preservation
            d.setBounds(0, 0, width, height)
            d.draw(canvas)

            // If it's animated, keep redrawing for the next frame
            if (d is AnimatedImageDrawable && d.isRunning) {
                postInvalidateOnAnimation()
            }
            return
        }

        // ── Movie path (animated GIF on pre-API-28) ──
        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            if (m.width() > 0 && m.height() > 0 && width > 0 && height > 0) {
                // STRETCH: independent X/Y scales so the image fills the view exactly
                val scaleX = width.toFloat() / m.width()
                val scaleY = height.toFloat() / m.height()

                canvas.save()
                canvas.scale(scaleX, scaleY)
                m.draw(canvas, 0f, 0f)
                canvas.restore()
            }

            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? AnimatedImageDrawable)?.start()
        if (movie != null) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    private companion object {
        const val TAG = "AnimatedBg"
    }
}