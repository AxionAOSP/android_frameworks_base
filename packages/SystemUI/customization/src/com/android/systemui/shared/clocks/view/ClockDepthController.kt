package com.android.systemui.shared.clocks.view

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import com.android.app.animation.Interpolators
import com.android.systemui.shared.clocks.DepthWallpaperProvider
import android.content.res.Resources
import android.provider.Settings
import android.util.DisplayMetrics

class ClockDepthController(private val view: View) {

    var enabled = true

    private var subjectPath: Path? = null
    private var pathAspect: Float = 1f
    private var depthActive = false
    private var depthVisible = true
    private var maskAlpha = 0f
    private var revealProgress = 0f
    private var maskAnimator: ValueAnimator? = null

    private val transformedPath = Path()
    private val pathMatrix = Matrix()
    private val location = IntArray(2)
    private var cachedZoom = 0f
    private var cachedScreenW = 0f
    private var cachedScreenH = 0f
    private var pathDirty = true
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val wallpaperMaxScale: Float = try {
        val id = Resources.getSystem().getIdentifier(
            "config_wallpaperMaxScale", "dimen", "android"
        )
        if (id != 0) Resources.getSystem().getFloat(id) else 1.1f
    } catch (_: Exception) { 1.1f }

    private fun getZoom(): Float {
        return try {
            val str = Settings.Secure.getString(
                view.context.contentResolver, "ax_depth_zoom"
            )
            str?.toFloatOrNull() ?: wallpaperMaxScale
        } catch (_: Exception) { wallpaperMaxScale }
    }

    private val listener = object : DepthWallpaperProvider.DepthMaskListener {
        override fun onDepthDataChanged(path: Path?, pathAspect: Float) {
            val wasActive = depthActive
            subjectPath = path
            this@ClockDepthController.pathAspect = pathAspect
            depthActive = path != null && !path.isEmpty
            pathDirty = true

            if (!depthVisible) {
                view.postInvalidateOnAnimation()
                return
            }

            maskAnimator?.cancel()
            if (depthActive && !wasActive) {
                animateReveal()
            } else if (!depthActive && wasActive) {
                animateHide()
            } else {
                view.postInvalidateOnAnimation()
            }
        }
    }

    fun onAttached() {
        if (!enabled) return
        DepthWallpaperProvider.init(view.context)
        DepthWallpaperProvider.addListener(listener)
    }

    fun onDetached() {
        maskAnimator?.cancel()
        maskAnimator = null
        maskAlpha = 0f
        revealProgress = 0f
        DepthWallpaperProvider.removeListener(listener)
        subjectPath = null
        depthActive = false
    }

    fun setDepthVisible(visible: Boolean) {
        if (depthVisible == visible) return
        depthVisible = visible

        if (!depthActive) return

        maskAnimator?.cancel()
        if (visible) {
            animateReveal()
        } else {
            maskAlpha = 0f
            revealProgress = 0f
            view.postInvalidateOnAnimation()
        }
    }

    fun shouldApplyDepth(): Boolean {
        val path = subjectPath
        return depthActive && depthVisible && path != null && !path.isEmpty && maskAlpha > 0f
    }

    fun drawWithDepth(canvas: Canvas, drawSuper: (Canvas) -> Unit) {
        val path = subjectPath ?: run { drawSuper(canvas); return }

        view.getLocationOnScreen(location)
        val viewX = location[0].toFloat()
        val viewY = location[1].toFloat()

        if (cachedScreenW == 0f) {
            val realMetrics = DisplayMetrics()
            view.context.display?.getRealMetrics(realMetrics)
            cachedScreenW = realMetrics.widthPixels.toFloat()
            cachedScreenH = realMetrics.heightPixels.toFloat()
            cachedZoom = getZoom()
            pathDirty = true
        }
        val screenW = cachedScreenW
        val screenH = cachedScreenH
        val zoom = cachedZoom

        val wallAspect = pathAspect
        val screenAspect = screenW / screenH
        val cropLeft: Float
        val cropTop: Float
        val visibleW: Float
        val visibleH: Float
        if (wallAspect > screenAspect) {
            visibleW = (screenAspect / wallAspect) * 10000f
            visibleH = 10000f
            cropLeft = (10000f - visibleW) / 2f
            cropTop = 0f
        } else {
            visibleW = 10000f
            visibleH = (wallAspect / screenAspect) * 10000f
            cropLeft = 0f
            cropTop = (10000f - visibleH) / 2f
        }

        if (pathDirty || revealProgress < 1f) {
            pathMatrix.reset()
            pathMatrix.setTranslate(-cropLeft, -cropTop)
            pathMatrix.postScale(screenW / visibleW, screenH / visibleH)
            if (zoom != 1f) {
                pathMatrix.postScale(zoom, zoom, screenW / 2f, screenH / 2f)
            }
            pathMatrix.postTranslate(-viewX, -viewY)

            val viewScaleX = view.scaleX
            val viewScaleY = view.scaleY
            if (viewScaleX != 1f || viewScaleY != 1f) {
                pathMatrix.postScale(1f / viewScaleX, 1f / viewScaleY)
            }

            transformedPath.reset()
            path.transform(pathMatrix, transformedPath)
            pathDirty = false
        }

        val pathBounds = RectF()
        transformedPath.computeBounds(pathBounds, true)

        val layerLeft = -viewX
        val layerTop = -viewY
        val layerRight = screenW - viewX
        val layerBottom = screenH - viewY
        val layerRect = RectF(layerLeft, layerTop, layerRight, layerBottom)

        if (!RectF.intersects(pathBounds, layerRect)) {
            drawSuper(canvas)
            return
        }

        if (revealProgress >= 1f && maskAlpha >= 1f) {
            canvas.save()
            canvas.clipOutPath(transformedPath)
            drawSuper(canvas)
            canvas.restore()
        } else {
            if (revealProgress < 1f) {
                val bounds = RectF()
                transformedPath.computeBounds(bounds, false)
                val revealMatrix = Matrix()
                val s = REVEAL_MIN_SCALE + (1f - REVEAL_MIN_SCALE) * revealProgress
                revealMatrix.setScale(s, s, bounds.centerX(), bounds.centerY())
                revealMatrix.postTranslate(0f, PARALLAX_PX * (1f - revealProgress))
                transformedPath.transform(revealMatrix)
            }

            val layerCount = canvas.saveLayer(layerLeft, layerTop, layerRight, layerBottom, null)
            drawSuper(canvas)

            maskPaint.alpha = (maskAlpha * 255f).toInt().coerceIn(0, 255)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            canvas.drawPath(transformedPath, maskPaint)
            maskPaint.xfermode = null
            maskPaint.alpha = 255

            canvas.restoreToCount(layerCount)
        }
    }

    private fun animateReveal() {
        maskAnimator?.cancel()
        maskAnimator = ValueAnimator.ofFloat(maskAlpha, 1f).apply {
            duration = REVEAL_DURATION
            interpolator = Interpolators.EMPHASIZED_DECELERATE
            addUpdateListener {
                val v = it.animatedValue as Float
                maskAlpha = v
                revealProgress = v
                view.postInvalidateOnAnimation()
            }
            start()
        }
    }

    private fun animateHide() {
        maskAnimator?.cancel()
        maskAnimator = ValueAnimator.ofFloat(maskAlpha, 0f).apply {
            duration = REVEAL_DURATION / 2
            interpolator = Interpolators.EMPHASIZED_ACCELERATE
            addUpdateListener {
                val v = it.animatedValue as Float
                maskAlpha = v
                revealProgress = v
                view.postInvalidateOnAnimation()
            }
            start()
        }
    }

    private companion object {
        const val REVEAL_DURATION = 600L
        const val REVEAL_MIN_SCALE = 0.97f
        const val PARALLAX_PX = 24f
    }
}
