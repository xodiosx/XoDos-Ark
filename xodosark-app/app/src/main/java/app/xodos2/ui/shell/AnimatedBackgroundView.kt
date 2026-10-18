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

        // ── GIF: prefer Movie (proven manual animation on all APIs) ──
        if (isGif(file)) {
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
                Log.w(TAG, "Movie failed, will try ImageDecoder", e)
            }
        }

        // ── Static images + animated WebP/APNG (API 28+) ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                val d = ImageDecoder.decodeDrawable(source)
                d.callback = this
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                }
                drawable = d
                // CRITICAL: AnimatedImageDrawable only advances frames when visible
                d.setVisible(true, false)
                if (d is AnimatedImageDrawable) d.start()
                Log.i(TAG, "ImageDecoder: ${d.javaClass.simpleName} $path")
                invalidate()
                return
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder failed, falling back", e)
            }
        }

        // ── Last resort: static bitmap ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val d = BitmapDrawable(resources, bmp).also {
                it.callback = this
                it.setVisible(true, false)
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
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── Drawable path ──
        drawable?.let { d ->
            d.setBounds(0, 0, width, height)
            d.draw(canvas)

            if (d is AnimatedImageDrawable) {
                // Safety net: keep the animation loop driving even if the
                // internal scheduling hiccups (e.g. on some OEM ROMs).
                if (!d.isVisible) d.setVisible(true, false)
                if (!d.isRunning) d.start()
                postInvalidateOnAnimation()
            }
            return
        }

        // ── Movie path — manual animation, works on every API ──
        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            if (m.width() > 0 && m.height() > 0 && width > 0 && height > 0) {
                // STRETCH to fill the view exactly
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
        val visible = visibility == View.VISIBLE
        drawable?.setVisible(visible, false)
        if (visible) {
            (drawable as? AnimatedImageDrawable)?.start()
        } else {
            (drawable as? AnimatedImageDrawable)?.stop()
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

    private fun isGif(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(6)
            val n = s.read(h)
            n == 6 && h[0].toInt() == 0x47 && h[1].toInt() == 0x49 && h[2].toInt() == 0x46
        }
    } catch (_: Exception) { false }

    private companion object {
        const val TAG = "AnimatedBg"
    }
}