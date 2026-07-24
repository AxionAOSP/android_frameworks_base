package com.android.systemui.shared.clocks.view

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Region
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import com.android.app.animation.Interpolators
import com.android.systemui.shared.clocks.DepthWallpaperProvider
import kotlin.math.ceil
import kotlin.math.floor

class ClockDepthController(private val view: View) {

    var enabled = true

    private var subjectPath: Path? = null
    private var pathAspect: Float = 1f
    private var depthActive = false
    private var depthVisible = true
    private var depthSuppressed = false
    private var maskAlpha = 0f
    private var revealProgress = 0f
    private var wallpaperZoomActive = false
    private var wallpaperZoomDisabled = false
    private var maskAnimator: ValueAnimator? = null

    private val transformedPath = Path()
    private val revealPath = Path()
    private val pathMatrix = Matrix()
    private val revealMatrix = Matrix()
    private val pathBounds = RectF()
    private val revealBounds = RectF()
    private val layerRect = RectF()
    private val coverageRegion = Region()
    private val coverageClip = Region()
    private val coverageDiff = Region()
    private val location = IntArray(2)
    private var cachedZoom = 0f
    private var cachedScreenW = 0f
    private var cachedScreenH = 0f
    private var cachedViewX = Float.NaN
    private var cachedViewY = Float.NaN
    private var cachedViewScaleX = Float.NaN
    private var cachedViewScaleY = Float.NaN
    private var pathDirty = true
    var sourceBoundsProvider: (() -> RectF?)? = null
        set(value) {
            if (field === value) return
            field = value
            resetTransformCache()
            view.postInvalidateOnAnimation()
        }
    var sourceScale = 1f
        set(value) {
            if (field == value) return
            field = value
            resetTransformCache()
            view.postInvalidateOnAnimation()
        }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val maskXfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private val wallpaperMinScale = systemWallpaperScale("config_wallpaperMinScale", 1f)
    private val wallpaperMaxScale = systemWallpaperScale("config_wallpaperMaxScale", 1.1f)

    private fun systemWallpaperScale(name: String, fallback: Float): Float {
        val id = Resources.getSystem().getIdentifier(name, "dimen", "android")
        if (id == 0) return fallback
        return try {
            view.context.resources.getFloat(id)
        } catch (_: Resources.NotFoundException) {
            fallback
        }
    }

    private fun getZoom(): Float {
        if (wallpaperZoomDisabled) return wallpaperMinScale
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

            if (isZoomEffectActive()) {
                hideDepth()
                return
            }
            if (depthActive && !wasActive) {
                animateReveal()
            } else if (!depthActive && wasActive) {
                animateHide()
            } else {
                view.postInvalidateOnAnimation()
            }
        }

        override fun onWallpaperZoomActiveChanged(active: Boolean) {
            if (wallpaperZoomActive == active) return
            wallpaperZoomActive = active
            if (wallpaperZoomDisabled) return
            if (active) {
                hideDepth()
                return
            }

            pathDirty = true
            if (depthActive && depthVisible) {
                animateReveal()
            } else {
                view.postInvalidateOnAnimation()
            }
        }

        override fun onWallpaperZoomDisabledChanged(disabled: Boolean) {
            if (wallpaperZoomDisabled == disabled) return
            wallpaperZoomDisabled = disabled
            resetTransformCache()
            if (isZoomEffectActive()) {
                hideDepth()
            } else if (depthActive && depthVisible && maskAlpha < 1f) {
                animateReveal()
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
        depthSuppressed = false
    }

    fun setDepthVisible(visible: Boolean) {
        if (depthVisible == visible) return
        depthVisible = visible

        if (!depthActive) return

        if (visible) {
            if (isZoomEffectActive()) {
                hideDepth()
            } else {
                animateReveal()
            }
        } else {
            hideDepth()
        }
    }

    fun shouldApplyDepth(): Boolean {
        val path = subjectPath
        return depthActive &&
            depthVisible &&
            path != null &&
            !path.isEmpty &&
            maskAlpha > 0f &&
            !isZoomEffectActive()
    }

    fun drawWithDepth(canvas: Canvas, drawSuper: (Canvas) -> Unit) {
        val path = subjectPath ?: run { drawSuper(canvas); return }

        val boundsProvider = sourceBoundsProvider
        val sourceBounds = boundsProvider?.invoke()
        val boundsW = sourceBounds?.width() ?: 0f
        val boundsH = sourceBounds?.height() ?: 0f
        val useSourceBounds = boundsW > 0f && boundsH > 0f
        if (boundsProvider != null && !useSourceBounds) {
            drawSuper(canvas)
            return
        }
        if (useSourceBounds) {
            view.getLocationInWindow(location)
        } else {
            view.getLocationOnScreen(location)
        }
        val viewX = location[0].toFloat()
        val viewY = location[1].toFloat()
        val viewScaleX = view.scaleX
        val viewScaleY = view.scaleY
        val nextScreenW: Float
        val nextScreenH: Float
        val nextViewX: Float
        val nextViewY: Float
        val nextZoom: Float

        if (useSourceBounds && sourceBounds != null) {
            nextScreenW = boundsW / sourceScale
            nextScreenH = boundsH / sourceScale
            nextViewX = (viewX - sourceBounds.left) / sourceScale
            nextViewY = (viewY - sourceBounds.top) / sourceScale
            nextZoom = 1f
        } else {
            val realMetrics = DisplayMetrics()
            view.context.display?.getRealMetrics(realMetrics)
            nextScreenW = realMetrics.widthPixels.toFloat()
            nextScreenH = realMetrics.heightPixels.toFloat()
            nextViewX = viewX
            nextViewY = viewY
            nextZoom = if (cachedScreenW == 0f) getZoom() else cachedZoom
        }

        if (
            cachedScreenW != nextScreenW ||
                cachedScreenH != nextScreenH ||
                cachedViewX != nextViewX ||
                cachedViewY != nextViewY ||
                cachedViewScaleX != viewScaleX ||
                cachedViewScaleY != viewScaleY ||
                cachedZoom != nextZoom
        ) {
            cachedScreenW = nextScreenW
            cachedScreenH = nextScreenH
            cachedViewX = nextViewX
            cachedViewY = nextViewY
            cachedViewScaleX = viewScaleX
            cachedViewScaleY = viewScaleY
            cachedZoom = nextZoom
            pathDirty = true
        }

        val screenW = cachedScreenW
        val screenH = cachedScreenH
        val translatedViewX = cachedViewX
        val translatedViewY = cachedViewY
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

        if (pathDirty) {
            pathMatrix.reset()
            pathMatrix.setTranslate(-cropLeft, -cropTop)
            pathMatrix.postScale(screenW / visibleW, screenH / visibleH)
            if (zoom != 1f) {
                pathMatrix.postScale(zoom, zoom, screenW / 2f, screenH / 2f)
            }
            pathMatrix.postTranslate(-translatedViewX, -translatedViewY)

            if (cachedViewScaleX != 1f || cachedViewScaleY != 1f) {
                pathMatrix.postScale(1f / cachedViewScaleX, 1f / cachedViewScaleY)
            }

            transformedPath.reset()
            path.transform(pathMatrix, transformedPath)
            pathDirty = false
        }

        transformedPath.computeBounds(pathBounds, true)

        val layerLeft = -translatedViewX
        val layerTop = -translatedViewY
        val layerRight = screenW - translatedViewX
        val layerBottom = screenH - translatedViewY
        layerRect.set(layerLeft, layerTop, layerRight, layerBottom)

        if (!RectF.intersects(pathBounds, layerRect)) {
            drawSuper(canvas)
            return
        }

        val suppressed = pathBounds.contains(layerRect) && pathFullyCovers(layerRect)
        if (suppressed) {
            if (maskAnimator?.isRunning == true) {
                maskAnimator?.cancel()
            }
            maskAlpha = 0f
            revealProgress = 0f
            depthSuppressed = true
            drawSuper(canvas)
            return
        }
        if (depthSuppressed) {
            depthSuppressed = false
            if (depthActive && depthVisible) {
                animateReveal()
            }
        }

        if (revealProgress >= 1f && maskAlpha >= 1f) {
            canvas.save()
            canvas.clipOutPath(transformedPath)
            drawSuper(canvas)
            canvas.restore()
        } else {
            var maskPath = transformedPath
            if (revealProgress < 1f) {
                transformedPath.computeBounds(revealBounds, false)
                val s = REVEAL_MIN_SCALE + (1f - REVEAL_MIN_SCALE) * revealProgress
                revealMatrix.setScale(s, s, revealBounds.centerX(), revealBounds.centerY())
                revealMatrix.postTranslate(0f, PARALLAX_PX * (1f - revealProgress))
                revealPath.reset()
                transformedPath.transform(revealMatrix, revealPath)
                maskPath = revealPath
            }

            val layerCount = canvas.saveLayer(layerLeft, layerTop, layerRight, layerBottom, null)
            drawSuper(canvas)

            maskPaint.alpha = (maskAlpha * 255f).toInt().coerceIn(0, 255)
            maskPaint.xfermode = maskXfermode
            canvas.drawPath(maskPath, maskPaint)
            maskPaint.xfermode = null
            maskPaint.alpha = 255

            canvas.restoreToCount(layerCount)
        }
    }

    private fun isZoomEffectActive(): Boolean = wallpaperZoomActive && !wallpaperZoomDisabled

    private fun hideDepth() {
        maskAnimator?.cancel()
        maskAnimator = null
        maskAlpha = 0f
        revealProgress = 0f
        view.postInvalidateOnAnimation()
    }

    private fun pathFullyCovers(layerRect: RectF): Boolean {
        coverageClip.set(
            floor(layerRect.left).toInt(),
            floor(layerRect.top).toInt(),
            ceil(layerRect.right).toInt(),
            ceil(layerRect.bottom).toInt()
        )
        coverageRegion.setPath(transformedPath, coverageClip)
        coverageDiff.set(coverageClip)
        coverageDiff.op(coverageRegion, Region.Op.DIFFERENCE)
        return coverageDiff.isEmpty
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

    private fun resetTransformCache() {
        cachedScreenW = 0f
        cachedScreenH = 0f
        cachedZoom = 0f
        cachedViewX = Float.NaN
        cachedViewY = Float.NaN
        cachedViewScaleX = Float.NaN
        cachedViewScaleY = Float.NaN
        pathDirty = true
    }

    private companion object {
        const val REVEAL_DURATION = 600L
        const val REVEAL_MIN_SCALE = 0.97f
        const val PARALLAX_PX = 24f
    }
}
