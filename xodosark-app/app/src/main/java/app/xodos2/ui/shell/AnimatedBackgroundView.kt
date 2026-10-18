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
 *  • API 28+  → ImageDecoder → AnimatedImageDrawable (handles ALL GIF variants)
 *  • API < 28 → Movie (limited GIF support — only fallback when we can't do better)
 *
 * Scaling: **STRETCH** — image fills the whole view (no crop, no letterbox).
 */
class AnimatedBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var drawable: Drawable? = null
    private var movieStartTime: Long = 0L
    private var currentSource: String? = null     // idempotence guard

    fun setSource(path: String?) {
        // Cheap guard: update() calls us on every recomposition.
        // Re-decoding the same file resets the GIF frame and looks frozen.
        if (currentSource == path) return

        clear()
        currentSource = path
        if (path.isNullOrBlank()) return

        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "setSource: not a file — $path")
            currentSource = null
            return
        }

        // ── API 28+: ImageDecoder handles ALL animated formats correctly ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                val d = ImageDecoder.decodeDrawable(source)
                d.callback = this
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                }
                drawable = d

                // Only mark visible/start when actually attached & shown —
                // calling setVisible(true) before attachment makes the
                // platform discard the animation state.
                val visible = isAttachedToWindow && isShown
                d.setVisible(visible, false)
                if (d is AnimatedImageDrawable && visible) d.start()

                Log.i(TAG, "ImageDecoder: ${d.javaClass.simpleName} $path")
                invalidate()
                return
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder failed, trying Movie/Bitmap", e)
            }
        }

        // ── Fallback: Movie (API < 28, or when ImageDecoder fails) ──
        try {
            val m = Movie.decodeFile(path)
            if (m != null && m.width() > 0 && m.height() > 0) {
                movie = m
                movieStartTime = SystemClock.uptimeMillis()
                Log.i(TAG, "Movie: ${m.width()}x${m.height()} $path")
                invalidate()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Movie failed", e)
        }

        // ── Static bitmap fallback ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val d = BitmapDrawable(resources, bmp).also {
                it.callback = this
                it.setVisible(isAttachedToWindow && isShown, false)
            }
            drawable = d
            Log.i(TAG, "Bitmap: ${bmp.width}x${bmp.height} $path")
            invalidate()
        } ?: Log.e(TAG, "Could not decode $path with any method")
    }

    fun clear() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable?.setVisible(false, false)
        drawable = null
        movie = null
        currentSource = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── Drawable path (static + AnimatedImageDrawable) ──
        drawable?.let { d ->
            d.setBounds(0, 0, width, height)   // STRETCH
            d.draw(canvas)

            if (d is AnimatedImageDrawable) {
                // Safety net: if the internal scheduler hiccups (common on
                // some OEM ROMs), force the animation back to life.
                if (!d.isVisible) d.setVisible(true, false)
                if (!d.isRunning) d.start()
                postInvalidateOnAnimation()
            }
            return
        }

        // ── Movie path ──
        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            if (m.width() > 0 && m.height() > 0 && width > 0 && height > 0) {
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        val visible = visibility == View.VISIBLE && isAttachedToWindow
        drawable?.setVisible(visible, false)
        if (drawable is AnimatedImageDrawable) {
            if (visible) (drawable as AnimatedImageDrawable).start()
            else (drawable as AnimatedImageDrawable).stop()
        }
        if (movie != null && visible) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawable?.setVisible(isShown, false)
        if (isShown) (drawable as? AnimatedImageDrawable)?.start()
        if (movie != null) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drawable?.setVisible(false, false)
        (drawable as? AnimatedImageDrawable)?.stop()
    }

    private companion object {
        const val TAG = "AnimatedBg"
    }
}