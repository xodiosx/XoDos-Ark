package app.xodos2.ui.shell

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.annotation.RequiresApi
import java.io.File

/**
 * Background view — static images + animated GIF / WebP / APNG.
 *
 * ── API 28+ (Android 9+) ──────────────────────────────────────
 *   Every animated format via [android.graphics.ImageDecoder] and
 *   [android.graphics.drawable.AnimatedImageDrawable].
 *
 * ── API 24–27 (Android 7.0–8.1) ───────────────────────────────
 *   GIF  → [android.graphics.Movie] (animated)
 *   PNG / JPG / WebP first frame → [BitmapFactory] (static)
 *   APNG / animated WebP → shown as a static first frame only
 *
 * **Crucially**, this class contains *zero* direct references to any
 * API‑28 class. All API‑28 code lives in [Api28ImageHelper], whose class
 * is only loaded on API 28+ — on older devices the classloader never
 * tries to resolve `ImageDecoder` / `AnimatedImageDrawable`, so the app
 * does not crash with `NoClassDefFoundError`.
 *
 * Rendering: **STRETCH** — the image fills the entire view exactly;
 * aspect ratio is not preserved.
 *
 * Cache key = path + file size + last modified, so picking a new image
 * that saves to the same path forces a reload.
 */
class AnimatedBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var drawable: Drawable? = null
    private var movieStartTime: Long = 0L
    private var currentKey: String? = null

    fun setSource(path: String?) {
        val key: String? = if (path.isNullOrBlank()) {
            null
        } else {
            val f = File(path)
            if (f.isFile) "$path|${f.length()}|${f.lastModified()}" else null
        }

        if (currentKey == key) return
        currentKey = key

        clearInternal()

        if (path.isNullOrBlank()) return
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "setSource: not a file — $path")
            return
        }

        // ── API 28+: ImageDecoder handles every animated format ──
        // The helper class is only *loaded* when this branch runs, so on
        // API 24–27 `ImageDecoder` and `AnimatedImageDrawable` are never
        // resolved by the classloader.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val d = Api28ImageHelper.decodeDrawable(file)
            if (d != null) {
                d.callback = this
                d.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
                val visible = isAttachedToWindow && isShown
                d.setVisible(visible, false)
                Api28ImageHelper.configureAndStart(d, visible)
                drawable = d
                Log.i(TAG, "ImageDecoder: ${d.javaClass.simpleName} " +
                        "intrinsic=${d.intrinsicWidth}x${d.intrinsicHeight} $path")
                invalidate()
                return
            }
            Log.w(TAG, "ImageDecoder failed for $path, falling back")
        }

        // ── API < 28 fallback: Movie for GIF (animated) ──
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

        // ── Static fallback: BitmapFactory (first frame only) ──
        BitmapFactory.decodeFile(path)?.let { bmp ->
            val d = BitmapDrawable(resources, bmp).also {
                it.callback = this
                it.setVisible(isAttachedToWindow && isShown, false)
                it.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
            drawable = d
            Log.i(TAG, "Bitmap: ${bmp.width}x${bmp.height} $path")
            invalidate()
        } ?: Log.e(TAG, "Could not decode $path with any method")
    }

    fun clear() {
        currentKey = null
        clearInternal()
    }

    private fun clearInternal() {
        // Route through the helper so this method has no direct reference
        // to AnimatedImageDrawable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28ImageHelper.stopIfAnimated(drawable)
        }
        drawable?.setVisible(false, false)
        drawable = null
        movie = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawable?.setBounds(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // ── Drawable path (static + animated) ──
        drawable?.let { d ->
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight

            if (iw > 0 && ih > 0) {
                // STRETCH: draw at intrinsic size inside a scaled canvas.
                // Works around OEM ROMs where AnimatedImageDrawable
                // ignores setBounds().
                val scaleX = width.toFloat() / iw
                val scaleY = height.toFloat() / ih
                canvas.save()
                canvas.scale(scaleX, scaleY)
                d.setBounds(0, 0, iw, ih)
                d.draw(canvas)
                canvas.restore()
            } else {
                d.setBounds(0, 0, width, height)
                d.draw(canvas)
            }

            // Keep animated drawables ticking — helper isolates the API-28 cast.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (Api28ImageHelper.ensureRunning(d)) {
                    postInvalidateOnAnimation()
                }
            }
            return
        }

        // ── Movie path (API < 28 GIF fallback) ──
        movie?.let { m ->
            val now = SystemClock.uptimeMillis()
            val duration = if (m.duration() > 0) m.duration() else 1000
            val relTime = ((now - movieStartTime) % duration).toInt()
            m.setTime(relTime)

            if (m.width() > 0 && m.height() > 0) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28ImageHelper.setAnimatedVisible(drawable, visible)
        }
        if (movie != null && visible) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        drawable?.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
        drawable?.setVisible(isShown, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28ImageHelper.setAnimatedVisible(drawable, isShown)
        }
        if (movie != null) {
            movieStartTime = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        drawable?.setVisible(false, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Api28ImageHelper.setAnimatedVisible(drawable, false)
        }
    }

    private companion object {
        const val TAG = "AnimatedBg"
    }
}

/**
 * All API‑28+ references live here.
 *
 * The compiler emits references to this object from
 * [AnimatedBackgroundView], but every call site is guarded by
 * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P`. On API 24–27 the
 * guarded branches are never taken → this object is never loaded → the
 * classloader never tries to resolve [android.graphics.ImageDecoder] or
 * [android.graphics.drawable.AnimatedImageDrawable].
 *
 * Do not annotate this object with anything that would cause it to be
 * loaded eagerly at class‑init time, and do not reference it from any
 * method that is not SDK‑guarded.
 */
@RequiresApi(Build.VERSION_CODES.P)
private object Api28ImageHelper {

    /** Decode via ImageDecoder — animated or static, decided by the file. */
    fun decodeDrawable(file: File): Drawable? {
        return try {
            val source = android.graphics.ImageDecoder.createSource(file)
            android.graphics.ImageDecoder.decodeDrawable(source)
        } catch (e: Exception) {
            Log.w("Api28ImageHelper", "decodeDrawable failed", e)
            null
        }
    }

    /** Make the drawable loop forever (if it is animated) and start it. */
    fun configureAndStart(d: Drawable, visible: Boolean) {
        if (d !is android.graphics.drawable.AnimatedImageDrawable) return
        d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
        if (visible) d.start()
    }

    /** Stop the animation if the drawable is animated. Safe for statics. */
    fun stopIfAnimated(d: Drawable?) {
        if (d is android.graphics.drawable.AnimatedImageDrawable && d.isRunning) {
            d.stop()
        }
    }

    /** Start / stop in response to visibility changes. */
    fun setAnimatedVisible(d: Drawable?, visible: Boolean) {
        if (d !is android.graphics.drawable.AnimatedImageDrawable) return
        if (visible) {
            if (!d.isRunning) d.start()
        } else {
            if (d.isRunning) d.stop()
        }
    }

    /**
     * Returns `true` when the drawable is animated and should be ticked
     * again on the next frame. Returns `false` for static drawables so we
     * don't needlessly spin the frame loop.
     */
    fun ensureRunning(d: Drawable): Boolean {
        if (d !is android.graphics.drawable.AnimatedImageDrawable) return false
        if (!d.isVisible) d.setVisible(true, false)
        if (!d.isRunning) d.start()
        return true
    }
}