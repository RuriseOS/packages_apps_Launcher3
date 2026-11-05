/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.graphics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Matrix.ScaleToFit.FILL
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.util.FloatProperty
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.graphics.PathParser
import androidx.dynamicanimation.animation.DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.SvgPathParser
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import com.android.launcher3.Flags
import com.android.launcher3.anim.SpringAnimationBuilder
import com.android.launcher3.folder.Folder
import com.android.launcher3.folder.FolderPagedView
import com.android.launcher3.icons.RoundRectEstimator.estimateRadius
import com.android.launcher3.views.ClipPathView
import kotlin.math.min
import kotlin.math.sqrt

/** Abstract representation of the shape of an icon shape */
interface ShapeDelegate {

    fun getPath(pathSize: Float = DEFAULT_PATH_SIZE) =
        Path().apply { addToPath(this, 0f, 0f, pathSize / 2) }

    fun getPath(bounds: Rect) =
        Path().apply {
            addToPath(
                this,
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                // Radius is half of the average size of the icon
                (bounds.width() + bounds.height()) / 4f,
            )
        }

    fun drawShape(canvas: Canvas, offsetX: Float, offsetY: Float, radius: Float, paint: Paint)

    fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float)

    fun addToPath(path: PathWrapper, offsetX: Float, offsetY: Float, radius: Float)

    fun <T> createRevealAnimator(
        target: T,
        startRect: Rect,
        endRect: Rect,
        endRadius: Float,
        isReversed: Boolean,
    ): ValueAnimator where T : View, T : ClipPathView

    /**
     * Returns the largest corner radii of a rounded rect filling the icon bounds that still
     * contains this shape, as ratios of the bounds size, in the order top-left, top-right,
     * bottom-left, bottom-right.
     */
    fun getEnclosingCornerRadii(): FloatArray

    /** Returns the smallest of [getEnclosingCornerRadii]. */
    fun getMinEnclosingCornerRadius(): Float = getEnclosingCornerRadii().min()

    /**
     * Like [createRevealAnimator], but ends on the rounded rect enclosing this shape. [endRadius]
     * is ignored by shapes whose enclosing rounded rect has distinct corner radii.
     */
    fun <T> createOpenRevealAnimator(
        target: T,
        startRect: Rect,
        endRect: Rect,
        endRadius: Float,
    ): ValueAnimator where T : View, T : ClipPathView =
        createRevealAnimator(target, startRect, endRect, endRadius, false /* isReversed */)

    class Circle : RoundedSquare(1f) {

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) = canvas.drawCircle(radius + offsetX, radius + offsetY, radius, paint)

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) =
            path.addCircle(radius + offsetX, radius + offsetY, radius, Path.Direction.CW)

        override fun addToPath(path: PathWrapper, offsetX: Float, offsetY: Float, radius: Float) {
            addToPath(path.path, offsetX, offsetY, radius)
            path.setBounds(offsetX, offsetY, offsetX + radius * 2, offsetY + radius * 2)
            path.cornerRadius = radius
        }
    }

    /** Rounded square with [radiusRatio] as a ratio of its half edge size */
    open class RoundedSquare(val radiusRatio: Float) : ShapeDelegate {

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * radiusRatio
            canvas.drawRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, cr, cr, paint)
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * radiusRatio
            path.addRoundRect(
                cx - radius,
                cy - radius,
                cx + radius,
                cy + radius,
                cr,
                cr,
                Path.Direction.CW,
            )
        }

        override fun addToPath(path: PathWrapper, offsetX: Float, offsetY: Float, radius: Float) {
            addToPath(path.path, offsetX, offsetY, radius)
            val cx = radius + offsetX
            val cy = radius + offsetY
            val cr = radius * radiusRatio
            path.setBounds(cx - radius, cy - radius, cx + radius, cy + radius)
            path.cornerRadius = cr
        }

        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView {
            val startRadius = (startRect.width() / 2f) * radiusRatio
            val pathProvider = { progress: Float, path: PathWrapper ->
                val radius = (1 - progress) * startRadius + progress * endRadius
                val left = (1 - progress) * startRect.left + progress * endRect.left
                val top = (1 - progress) * startRect.top + progress * endRect.top
                val right = (1 - progress) * startRect.right + progress * endRect.right
                val bottom = (1 - progress) * startRect.bottom + progress * endRect.bottom
                path.path.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
                path.setBounds(left, top, right, bottom)
                path.cornerRadius = radius
            }
            return if (useFolderSpringAnimation(target)) {
                ClipSpringAnimBuilder(target, pathProvider).toAnim(isReversed)
            } else {
                ClipAnimBuilder(target, pathProvider).toAnim(isReversed)
            }
        }

        override fun getEnclosingCornerRadii() = FloatArray(4) { radiusRatio / 2 }

        override fun equals(other: Any?) =
            other is RoundedSquare && other.radiusRatio == radiusRatio

        override fun hashCode() = radiusRatio.hashCode()
    }

    /** Generic shape delegate with pathString in bounds [0, 0, 100, 100] */
    data class GenericPathShape(private val pathString: String) : ShapeDelegate {
        private val poly =
            RoundedPolygon(
                features = SvgPathParser.parseFeatures(pathString),
                centerX = 50f,
                centerY = 50f,
            )
        // This ensures that a valid morph is possible from the provided path
        private val basePath =
            Path().apply {
                Morph(poly, createRoundedRect(0f, 0f, 100f, 100f, 25f)).toPath(0f, this)
            }
        private val tmpPath = Path()
        private val tmpMatrix = Matrix()
        private val enclosingRadiusRatios by lazy {
            val radii = FloatArray(4) { 0.5f }
            val points = basePath.approximate(0.1f)
            for (i in points.indices step 3) {
                val x = points[i + 1]
                val y = points[i + 2]
                val dx = min(x, DEFAULT_PATH_SIZE - x).coerceAtLeast(0f)
                val dy = min(y, DEFAULT_PATH_SIZE - y).coerceAtLeast(0f)
                // Solves (r - dx)^2 + (r - dy)^2 = r^2 for a corner arc going through the point.
                val radius = dx + dy + sqrt(2 * dx * dy)
                val corner =
                    (if (x < DEFAULT_PATH_SIZE / 2) 0 else 1) +
                        (if (y < DEFAULT_PATH_SIZE / 2) 0 else 2)
                radii[corner] = min(radii[corner], radius / DEFAULT_PATH_SIZE)
            }
            radii
        }

        override fun drawShape(
            canvas: Canvas,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            paint: Paint,
        ) {
            tmpPath.reset()
            addToPath(tmpPath, offsetX, offsetY, radius, tmpMatrix)
            canvas.drawPath(tmpPath, paint)
        }

        override fun addToPath(path: Path, offsetX: Float, offsetY: Float, radius: Float) {
            addToPath(path, offsetX, offsetY, radius, Matrix())
        }

        override fun addToPath(path: PathWrapper, offsetX: Float, offsetY: Float, radius: Float) {
            addToPath(path.path, offsetX, offsetY, radius, Matrix())
            path.estimateBoundsFromPath()
        }

        private fun addToPath(
            path: Path,
            offsetX: Float,
            offsetY: Float,
            radius: Float,
            matrix: Matrix,
        ) {
            matrix.setScale(radius / 50, radius / 50)
            matrix.postTranslate(offsetX, offsetY)
            basePath.transform(matrix, path)
        }

        override fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView =
            createRevealAnimator(
                target,
                startRect,
                // End poly is defined as a rectangle starting at top/center so that the
                // transformation has minimum motion
                createRoundedRect(
                    left = endRect.left.toFloat(),
                    top = endRect.top.toFloat(),
                    right = endRect.right.toFloat(),
                    bottom = endRect.bottom.toFloat(),
                    cornerR = endRadius,
                ),
                isReversed,
            )

        override fun getEnclosingCornerRadii() = enclosingRadiusRatios

        override fun <T> createOpenRevealAnimator(
            target: T,
            startRect: Rect,
            endRect: Rect,
            endRadius: Float,
        ): ValueAnimator where T : View, T : ClipPathView =
            createRevealAnimator(
                target,
                startRect,
                createRoundedRect(endRect, enclosingRadiusRatios),
                false /* isReversed */,
            )

        private fun <T> createRevealAnimator(
            target: T,
            startRect: Rect,
            endPoly: RoundedPolygon,
            isReversed: Boolean,
        ): ValueAnimator where T : View, T : ClipPathView {
            val morph =
                Morph(
                    start =
                        poly.transformed(
                            Matrix().apply {
                                setRectToRect(
                                    RectF(0f, 0f, DEFAULT_PATH_SIZE, DEFAULT_PATH_SIZE),
                                    RectF(startRect),
                                    FILL,
                                )
                            }
                        ),
                    end = endPoly,
                )
            val pathProvider = { progress: Float, path: PathWrapper ->
                path.path.set(morph.toPath(progress, path.path))
                path.estimateBoundsFromPath()
            }
            return if (useFolderSpringAnimation(target)) {
                ClipSpringAnimBuilder(target, pathProvider).toAnim(isReversed)
            } else {
                ClipAnimBuilder(target, pathProvider).toAnim(isReversed)
            }
        }
    }

    private class ClipAnimBuilder<T>(
        val target: T,
        val pathProvider: (Float, PathWrapper) -> Unit
    ) :
        AnimatorListenerAdapter(), AnimatorUpdateListener where T : View, T : ClipPathView {

        private var oldOutlineProvider: ViewOutlineProvider? = null
        val path = PathWrapper()

        override fun onAnimationStart(animation: Animator) {
            target.apply {
                oldOutlineProvider = outlineProvider
                outlineProvider = null
                translationZ = -target.elevation
            }
        }

        override fun onAnimationEnd(animation: Animator) {
            target.apply {
                translationZ = 0f
                setClipPath(null)
                outlineProvider = oldOutlineProvider
            }
        }

        override fun onAnimationUpdate(anim: ValueAnimator) {
            path.reset()
            pathProvider.invoke(anim.animatedValue as Float, path)
            target.setClipPath(path)
        }

        fun toAnim(isReversed: Boolean) =
            (if (isReversed) ValueAnimator.ofFloat(1f, 0f) else ValueAnimator.ofFloat(0f, 1f))
                .also {
                    it.addListener(this)
                    it.addUpdateListener(this)
                }
    }

    private class ClipSpringAnimBuilder<T>(
        val target: T,
        val pathProvider: (Float, PathWrapper) -> Unit
    ) :
        AnimatorListenerAdapter() where T : View, T : ClipPathView {

        private var oldOutlineProvider: ViewOutlineProvider? = null
        val path = PathWrapper()
        private val animatorBuilder = SpringAnimationBuilder(target.context)
        private val progressProperty =
            object : FloatProperty<ClipSpringAnimBuilder<T>>("progress") {
                override fun setValue(obj: ClipSpringAnimBuilder<T>, value: Float) {
                    // Don't want to go below 0 or above 1 for progress.
                    val clampedValue = minOf(maxOf(value, 0f), 1f)
                    path.reset()
                    pathProvider.invoke(clampedValue, path)
                    target.setClipPath(path)
                }

                override fun get(obj: ClipSpringAnimBuilder<T>): Float {
                    return 0f
                }
            }

        override fun onAnimationStart(animation: Animator) {
            target.apply {
                oldOutlineProvider = outlineProvider
                outlineProvider = null
                translationZ = -target.elevation
            }
        }

        override fun onAnimationEnd(animation: Animator) {
            target.apply {
                translationZ = 0f
                outlineProvider = oldOutlineProvider
            }
        }

        fun toAnim(isReversed: Boolean): ValueAnimator {
            val mStartValue = if (isReversed) 1f else 0f
            val mEndValue = if (isReversed) 0f else 1f
            pathProvider.invoke(mStartValue, path)
            target.setClipPath(path)
            val animator =
                animatorBuilder
                    .setStiffness(SPRING_STIFFNESS_SHAPE_POSITION)
                    .setDampingRatio(SPRING_DAMPING_SHAPE_POSITION)
                    .setStartValue(mStartValue)
                    .setEndValue(mEndValue)
                    .setMinimumVisibleChange(MIN_VISIBLE_CHANGE_SCALE)
                    .build(this, progressProperty)
            animator.addListener(this)
            return animator
        }
    }

    companion object {
        const val TAG = "IconShape"
        const val DEFAULT_PATH_SIZE_INT = 100
        const val DEFAULT_PATH_SIZE = DEFAULT_PATH_SIZE_INT.toFloat()
        private const val SPRING_STIFFNESS_SHAPE_POSITION = 380f
        private const val SPRING_DAMPING_SHAPE_POSITION = 0.8f

        fun pickBestShape(shapeStr: String): ShapeDelegate {
            val baseShape =
                if (shapeStr.isNotEmpty()) {
                    PathParser.createPathFromPathData(shapeStr)
                } else {
                    AdaptiveIconDrawable(null, ColorDrawable(Color.BLACK)).let {
                        it.setBounds(0, 0, DEFAULT_PATH_SIZE_INT, DEFAULT_PATH_SIZE_INT)
                        it.iconMask
                    }
                }
            return pickBestShape(baseShape, shapeStr)
        }

        fun pickBestShape(baseShape: Path, shapeStr: String): ShapeDelegate {
            val roundedRectRadiusRatio = estimateRadius(baseShape, DEFAULT_PATH_SIZE)
            // Use the generic shape only if we have more than .1% error
            if (shapeStr.isNotEmpty() && roundedRectRadiusRatio < 0) {
                try {
                    return GenericPathShape(shapeStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Error converting mask to generic shape", e)
                }
            }
            return when {
                roundedRectRadiusRatio >= 1f -> Circle()
                roundedRectRadiusRatio >= 0f -> RoundedSquare(roundedRectRadiusRatio)
                else -> Circle()
            }
        }

        /**
         * Create RoundedRect using RoundedPolygon API. Ensures smoother animation morphing between
         * generic polygon by using [RoundedPolygon.Companion.rectangle] directly.
         */
        fun createRoundedRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            cornerR: Float,
        ) =
            RoundedPolygon.rectangle(
                width = right - left,
                height = bottom - top,
                centerX = (right - left) / 2,
                centerY = (bottom - top) / 2,
                rounding = CornerRounding(cornerR),
            )

        /**
         * Creates a rounded rect at the origin with the size of [rect] and corner radii as
         * [radiusRatios] of its width, in the order top-left, top-right, bottom-left, bottom-right.
         */
        private fun createRoundedRect(rect: Rect, radiusRatios: FloatArray) =
            RoundedPolygon.rectangle(
                width = rect.width().toFloat(),
                height = rect.height().toFloat(),
                centerX = rect.width() / 2f,
                centerY = rect.height() / 2f,
                // The rectangle vertices start at the bottom-right corner and go clockwise.
                perVertexRounding =
                    listOf(3, 2, 0, 1).map { CornerRounding(rect.width() * radiusRatios[it]) },
            )
    }

    fun <T> useFolderSpringAnimation(target: T): Boolean {
        return (target is Folder || target is FolderPagedView)
            && Flags.enableLauncherIconShapes()
            && Flags.enableExpressiveFolderExpansion()
    }
}
