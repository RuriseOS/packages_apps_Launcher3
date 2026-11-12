/*
 * Copyright (C) 2026 The RuriseOS Project
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

package com.android.quickstep.unlock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.android.app.animation.Interpolators.FAST_OUT_SLOW_IN
import com.android.app.animation.Interpolators.LINEAR
import com.android.app.animation.Interpolators.TOUCH_RESPONSE
import com.android.app.animation.Interpolators.clampToProgress
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY
import com.android.launcher3.LauncherAnimUtils.VIEW_ALPHA
import com.android.launcher3.LauncherAnimUtils.VIEW_TRANSLATE_Y
import com.android.launcher3.R
import com.android.launcher3.anim.AnimatorPlaybackController
import com.android.launcher3.anim.PendingAnimation
import com.android.settingslib.widget.theme.R as ThemeR
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * A simplified home screen that keeps replaying an [UnlockAnimationStyle] through
 * [UnlockAnimationStyle.buildPreview]: it holds the locked state, plays the entrance with the
 * timing of a regular unlock, rests, then fades out and starts over.
 *
 * It is drawn as a phone with side buttons, like illustrations in Settings. Apps and widgets are
 * flat shapes in the theme colors, on a grid of the device's size. The locked state shows a clock
 * and a lock. Like illustrations in Settings, the playback can be paused.
 */
class UnlockStylePreviewView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    /** Id from [UnlockAnimationStyles] of the style to preview. */
    var styleId: String = UnlockAnimationStyles.ID_NONE
        set(value) {
            if (field != value) {
                field = value
                // Show the newly picked style even if the previous one was paused
                isPaused = false
                restart()
            }
        }

    private val idp = InvariantDeviceProfile.INSTANCE.get(context)
    private val columns = idp.numColumns.coerceAtLeast(1)
    private val rows = idp.numRows.coerceAtLeast(1)
    private val hotseatCount = idp.numShownHotseatIcons.coerceAtLeast(1)

    private val iconColors =
        intArrayOf(
            context.getColor(ThemeR.color.settingslib_materialColorPrimary),
            context.getColor(ThemeR.color.settingslib_materialColorTertiary),
            context.getColor(ThemeR.color.settingslib_materialColorSecondary),
        )
    private val widgetColors =
        intArrayOf(
            context.getColor(ThemeR.color.settingslib_materialColorPrimaryContainer),
            context.getColor(ThemeR.color.settingslib_materialColorTertiaryContainer),
        )
    private val framePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(ThemeR.color.settingslib_materialColorOutlineVariant)
        }

    /** The display of the phone, inside its frame. Holds everything that is animated. */
    private val screen = FrameLayout(context)
    /** Faded out between two runs, without touching the items' own properties. */
    private val fadeLayer = FrameLayout(context)
    /** Parent of the items; the default AOSP unlock moves it as a whole. */
    private val grid = GridLayer(context)
    private val items = ArrayList<UnlockPreviewItem>()
    private val hotseat: PreviewItemView
    /** Holds the lock screen content, which fades in at the start of each run. */
    private val lockLayer = FrameLayout(context)
    /** Shown while the preview is locked, fade out when the entrance starts. */
    private val lockClock = LockClockView(context)
    private val lockIcon = LockIconView(context)

    /** The entrance of the current run. */
    private var controller: AnimatorPlaybackController? = null
    /** Drives the runs: locked state, entrance, rest and fade out, repeated. */
    private var loop: ValueAnimator? = null
    private var running = false

    /** Whether the playback is paused, see [togglePause]. */
    var isPaused = false
        private set

    init {
        setWillNotDraw(false)
        screen.setBackgroundColor(
            context.getColor(ThemeR.color.settingslib_materialColorSurfaceContainerHighest)
        )
        screen.clipToOutline = true
        screen.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, screenCornerRadius)
                }
            }
        screen.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(screen, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen.addView(
            fadeLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        fadeLayer.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        screen.addView(
            lockLayer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        lockLayer.addView(lockClock, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        lockLayer.addView(lockIcon, LayoutParams(0, 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        addSampleItems()
        hotseat = PreviewItemView(context, ItemKind.HOTSEAT, 0)
        grid.addView(hotseat)
        resetToRest()
    }

    /** Whether the preview animates, which is not the case when animations are turned off. */
    val isPlaying: Boolean
        get() = loop != null

    /** Pauses or resumes the playback if it is playing, returns whether it is now paused. */
    fun togglePause(): Boolean {
        val loop = loop ?: return isPaused
        isPaused = !isPaused
        if (isPaused) loop.pause() else loop.resume()
        return isPaused
    }

    /**
     * Fills the grid with a typical layout: a wide widget on top, a row of apps, a square widget
     * beside more apps, and apps on the bottom row when there is room.
     */
    private fun addSampleItems() {
        val occupied = Array(rows) { BooleanArray(columns) }
        var colorIndex = 0

        fun add(kind: ItemKind, x: Int, y: Int, spanX: Int, spanY: Int) {
            if (x < 0 || y < 0 || x + spanX > columns || y + spanY > rows) return
            for (cy in y until y + spanY) for (cx in x until x + spanX) {
                if (occupied[cy][cx]) return
            }
            for (cy in y until y + spanY) for (cx in x until x + spanX) occupied[cy][cx] = true
            val view = PreviewItemView(context, kind, colorIndex++)
            grid.addView(view)
            items.add(UnlockPreviewItem(view, x, y, spanX, spanY))
        }

        val topSpan = if (rows >= 5) 2 else 1
        add(ItemKind.WIDGET, 0, 0, columns, topSpan)
        val iconRow = topSpan
        for (x in 0 until columns) add(ItemKind.ICON, x, iconRow, 1, 1)
        val widgetRow = iconRow + 1
        if (columns >= 4 && rows - widgetRow >= 2) {
            add(ItemKind.WIDGET, 0, widgetRow, 2, 2)
            for (y in widgetRow until widgetRow + 2) {
                for (x in 2 until columns) add(ItemKind.ICON, x, y, 1, 1)
            }
        }
        if (rows - 1 >= widgetRow + 3) {
            for (x in 0 until columns) add(ItemKind.ICON, x, rows - 1, 1, 1)
        }
    }

    /** Width of the phone body, without the buttons sticking out of it. */
    private var bodyWidth = 0f
    private var bezel = 0f
    /** How far the side buttons stick out of the body, kept on both sides to stay centered. */
    private var buttonOutset = 0f
    private var screenCornerRadius = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep the aspect ratio of the display within the given height
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val metrics = resources.displayMetrics
        val aspect =
            min(metrics.widthPixels, metrics.heightPixels).toFloat() /
                max(metrics.widthPixels, metrics.heightPixels)
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        bodyWidth = height * aspect
        buttonOutset = bodyWidth * BUTTON_OUTSET_RATIO
        if (bodyWidth + 2 * buttonOutset > maxWidth) {
            bodyWidth = maxWidth / (1 + 2 * BUTTON_OUTSET_RATIO)
            buttonOutset = bodyWidth * BUTTON_OUTSET_RATIO
        }
        val width = (bodyWidth + 2 * buttonOutset).toInt()
        bezel = bodyWidth * BEZEL_RATIO
        screenCornerRadius = max(0f, bodyWidth * BODY_CORNER_RATIO - bezel)
        (screen.layoutParams as LayoutParams).apply {
            setMargins(
                (buttonOutset + bezel).toInt(),
                bezel.toInt(),
                (buttonOutset + bezel).toInt(),
                bezel.toInt(),
            )
        }

        // The lock screen is laid out within the screen
        val screenWidth = bodyWidth - 2 * bezel
        val screenHeight = height - 2 * bezel
        val lockSize = (screenWidth * LOCK_SIZE_RATIO).toInt()
        val lockBottomMargin = (screenHeight * LOCK_BOTTOM_RATIO).toInt()
        (lockIcon.layoutParams as LayoutParams).apply {
            this.width = lockSize
            this.height = lockSize
            bottomMargin = lockBottomMargin
        }
        // Center the clock between the top of its area and the lock, which keeps it balanced
        val clockAreaTop = screenHeight * CLOCK_AREA_TOP_RATIO
        val lockTop = screenHeight - lockBottomMargin - lockSize
        val clockHeight = min(screenHeight * CLOCK_HEIGHT_RATIO, lockTop - clockAreaTop)
        (lockClock.layoutParams as LayoutParams).apply {
            this.height = clockHeight.toInt()
            topMargin = (clockAreaTop + (lockTop - clockAreaTop - clockHeight) / 2).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Positions are read when the animation is built
        if (running) post { restart() }
    }

    override fun onDraw(canvas: Canvas) {
        // Buttons on the right side, partly hidden behind the body
        val buttonLeft = width - buttonOutset - bezel
        val buttonRight = width.toFloat()
        val buttonRadius = buttonOutset
        canvas.drawRoundRect(
            buttonLeft,
            height * POWER_BUTTON_TOP_RATIO,
            buttonRight,
            height * (POWER_BUTTON_TOP_RATIO + POWER_BUTTON_LENGTH_RATIO),
            buttonRadius,
            buttonRadius,
            framePaint,
        )
        canvas.drawRoundRect(
            buttonLeft,
            height * VOLUME_BUTTON_TOP_RATIO,
            buttonRight,
            height * (VOLUME_BUTTON_TOP_RATIO + VOLUME_BUTTON_LENGTH_RATIO),
            buttonRadius,
            buttonRadius,
            framePaint,
        )
        // The body; the screen is drawn on top of it
        val bodyRadius = bodyWidth * BODY_CORNER_RATIO
        canvas.drawRoundRect(
            buttonOutset,
            0f,
            buttonOutset + bodyWidth,
            height.toFloat(),
            bodyRadius,
            bodyRadius,
            framePaint,
        )
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            restart()
        } else {
            // Play again when coming back, e.g. after the screen was turned off
            isPaused = false
            stop()
        }
    }

    override fun onDetachedFromWindow() {
        isPaused = false
        stop()
        super.onDetachedFromWindow()
    }

    private fun restart() {
        stop()
        if (isAttachedToWindow && isShown) {
            running = true
            startLoop()
        }
    }

    private fun stop() {
        running = false
        loop?.let {
            it.removeAllListeners()
            it.removeAllUpdateListeners()
            it.cancel()
        }
        loop = null
        controller = null
        resetToRest()
    }

    private fun startLoop() {
        if (!running || !ValueAnimator.areAnimatorsEnabled() || width == 0) return
        buildRun()
        val loop =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CYCLE_MS
                interpolator = LINEAR
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { showCycleTime(it.animatedFraction * CYCLE_MS) }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationRepeat(animation: Animator) = buildRun()
                    }
                )
            }
        this.loop = loop
        loop.start()
        if (isPaused) loop.pause()
    }

    /** Fraction of the entrance last applied, to skip frames where nothing moves. */
    private var shownEntranceFraction = Float.NaN

    /** Applies the state at [time] milliseconds into a run. */
    private fun showCycleTime(time: Float) {
        val controller = controller ?: return
        // The lock screen leaves first, then the home screen comes in. Overlapping both, as the
        // keyguard does, leaves a ghost of the clock over the apps in this small preview.
        val lockExit =
            FAST_OUT_SLOW_IN.getInterpolation(
                ((time - LOCKED_HOLD_MS) / LOCK_EXIT_MS).coerceIn(0f, 1f)
            )
        lockClock.alpha = 1f - lockExit
        lockIcon.alpha = 1f - lockExit
        // Most of a run is spent holding the locked or the home screen; only update the entrance
        // while it moves
        val entranceFraction = ((time - ENTRANCE_START_MS) / RUN_MS).coerceIn(0f, 1f)
        if (entranceFraction != shownEntranceFraction) {
            shownEntranceFraction = entranceFraction
            controller.setPlayFraction(entranceFraction)
        }
        lockLayer.alpha = (time / LOCK_FADE_IN_MS).coerceIn(0f, 1f)
        fadeLayer.alpha = 1f - ((time - FADE_OUT_START_MS) / FADE_OUT_MS).coerceIn(0f, 1f)
    }

    /** Builds the entrance of the next run and holds its start state, as on the keyguard. */
    private fun buildRun() {
        controller = null
        resetToRest()

        val style = UnlockAnimationStyles.fromId(styleId)
        val anim = PendingAnimation(RUN_MS)
        if (style != null) {
            val targets =
                UnlockPreviewTargets(
                    root = grid,
                    columns = columns,
                    rows = rows,
                    items = items,
                    hotseat = hotseat,
                    gridCenterX = grid.gridCenterX,
                    gridCenterY = grid.gridCenterY,
                )
            style.buildPreview(
                anim,
                targets,
                UnlockTiming.forDelay(UNLOCK_DURATION_MS, UNLOCK_DELAY_MS),
            )
        } else {
            buildDefaultUnlock(anim)
        }
        lockClock.updateTime()
        val controller = anim.createPlaybackController()
        this.controller = controller
        controller.setPlayFraction(0f)
        shownEntranceFraction = 0f
    }

    /**
     * Approximates SystemUI's default unlock, used when Launcher doesn't animate: the whole home
     * screen fades in while it scales up and rises from slightly below.
     */
    private fun buildDefaultUnlock(anim: PendingAnimation) {
        val total = (DEFAULT_UNLOCK_DELAY_MS + DEFAULT_UNLOCK_DURATION_MS).toFloat()
        val progress = clampToProgress(TOUCH_RESPONSE, DEFAULT_UNLOCK_DELAY_MS / total, 1f)
        // The default unlock is shorter than the canned one; let it end early.
        val end = total / RUN_MS
        val interpolator = clampToProgress(progress, 0f, end)
        grid.pivotX = grid.width / 2f
        grid.pivotY = grid.height * DEFAULT_UNLOCK_PIVOT_Y
        anim.addFloat(grid, VIEW_ALPHA, 0f, 1f, interpolator)
        anim.addFloat(grid, SCALE_PROPERTY, DEFAULT_UNLOCK_START_SCALE, 1f, interpolator)
        anim.addFloat(
            grid,
            VIEW_TRANSLATE_Y,
            grid.height * DEFAULT_UNLOCK_START_TRANSLATION_Y,
            0f,
            interpolator,
        )
    }

    private fun resetToRest() {
        fadeLayer.alpha = 1f
        lockLayer.alpha = 1f
        (items.map { it.view } + hotseat + grid + lockIcon + lockClock).forEach { view ->
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.rotationX = 0f
            view.rotationY = 0f
            view.resetPivot()
        }
        // At rest the home screen is unlocked
        lockIcon.alpha = 0f
        lockClock.alpha = 0f
    }

    /** Lays the items out on the device's grid, above a hotseat. */
    private inner class GridLayer(context: Context) : ViewGroup(context) {

        var gridCenterX = 0f
            private set

        var gridCenterY = 0f
            private set

        private var gridLeft = 0f
        private var gridTop = 0f
        private var cellWidth = 0f
        private var cellHeight = 0f
        private var hotseatTop = 0f
        private var hotseatBottom = 0f

        private fun computeGrid(width: Int, height: Int) {
            val sidePadding = width * SIDE_PADDING_RATIO
            gridLeft = sidePadding
            gridTop = height * STATUS_BAR_RATIO
            hotseatBottom = height * (1 - NAV_BAR_RATIO)
            hotseatTop = hotseatBottom - height * HOTSEAT_RATIO
            val gridBottom = hotseatTop - height * HOTSEAT_GAP_RATIO
            cellWidth = (width - 2 * sidePadding) / columns
            cellHeight = (gridBottom - gridTop) / rows
            gridCenterX = width / 2f
            gridCenterY = (gridTop + gridBottom) / 2f
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            computeGrid(width, height)
            items.forEach { item ->
                item.view.measure(exactly(cellWidth * item.spanX), exactly(cellHeight * item.spanY))
            }
            hotseat.measure(exactly(width - 2 * gridLeft), exactly(hotseatBottom - hotseatTop))
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            items.forEach { item ->
                val x = (gridLeft + cellWidth * item.cellX).toInt()
                val y = (gridTop + cellHeight * item.cellY).toInt()
                item.view.layout(x, y, x + item.view.measuredWidth, y + item.view.measuredHeight)
            }
            hotseat.layout(
                gridLeft.toInt(),
                hotseatTop.toInt(),
                gridLeft.toInt() + hotseat.measuredWidth,
                hotseatTop.toInt() + hotseat.measuredHeight,
            )
        }

        private fun exactly(size: Float) =
            MeasureSpec.makeMeasureSpec(max(0, size.toInt()), MeasureSpec.EXACTLY)
    }

    /** An app, a widget or the hotseat, drawn as flat shapes. */
    private inner class PreviewItemView(
        context: Context,
        private val kind: ItemKind,
        /** Picks the item's color. */
        private val colorIndex: Int,
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Shapes never overlap within an item, so fading it needs no offscreen buffer
        override fun hasOverlappingRendering() = false

        override fun onDraw(canvas: Canvas) {
            when (kind) {
                ItemKind.ICON -> {
                    val radius = min(width, height) * ICON_SIZE_RATIO / 2
                    drawIcon(canvas, width / 2f, height / 2f, radius, colorIndex)
                }
                ItemKind.HOTSEAT -> {
                    val slot = width.toFloat() / hotseatCount
                    val radius = min(slot, height.toFloat()) * ICON_SIZE_RATIO / 2
                    for (i in 0 until hotseatCount) {
                        drawIcon(canvas, slot * (i + 0.5f), height / 2f, radius, i + 1)
                    }
                }
                ItemKind.WIDGET -> {
                    paint.color = widgetColors[colorIndex % widgetColors.size]
                    val inset = min(width, height) * WIDGET_INSET_RATIO
                    val corner = min(width, height) * WIDGET_CORNER_RATIO
                    canvas.drawRoundRect(
                        inset,
                        inset,
                        width - inset,
                        height - inset,
                        corner,
                        corner,
                        paint,
                    )
                }
            }
        }

        private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, radius: Float, index: Int) {
            paint.color = iconColors[index % iconColors.size]
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    /**
     * The time on two lines, hours above minutes, like the default lock screen clock. It shows the
     * time a run starts at and doesn't tick.
     */
    private class LockClockView(context: Context) : View(context) {

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(CLOCK_FONT_FAMILY, Typeface.NORMAL)
                fontVariationSettings = CLOCK_FONT_VARIATIONS
                color = context.getColor(ThemeR.color.settingslib_materialColorPrimary)
            }
        private var hours = ""
        private var minutes = ""

        // Digits never overlap, so fading the clock needs no offscreen buffer
        override fun hasOverlappingRendering() = false

        fun updateTime() {
            val now = Date()
            val hourPattern = if (DateFormat.is24HourFormat(context)) "HH" else "hh"
            val newHours = DateFormat.format(hourPattern, now).toString()
            val newMinutes = DateFormat.format("mm", now).toString()
            if (newHours == hours && newMinutes == minutes) return
            hours = newHours
            minutes = newMinutes
            invalidate()
        }

        private val digitBounds = Rect()

        override fun onDraw(canvas: Canvas) {
            // Two lines of digits filling the height, with a small gap between them
            val gap = height * CLOCK_LINE_GAP_RATIO
            val digitHeight = (height - gap) / 2f
            paint.textSize = REFERENCE_TEXT_SIZE
            paint.getTextBounds("0", 0, 1, digitBounds)
            if (digitBounds.height() <= 0) return
            val scale = digitHeight / digitBounds.height()
            paint.textSize = REFERENCE_TEXT_SIZE * scale
            // Round digits overshoot the baseline a little, so place each line by the glyph's
            // top rather than by its baseline to keep them inside the view
            val baselineOffset = -digitBounds.top * scale

            // Like the keyguard clock, every digit gets a column as wide as the widest digit
            // shown, measured by its glyph rather than its advance, and is centered in it
            val digits = hours + minutes
            var columnWidth = 0f
            for (digit in digits) {
                columnWidth = max(columnWidth, glyphBounds(digit).width().toFloat())
            }
            columnWidth += digitHeight * CLOCK_DIGIT_GAP_RATIO
            drawDigits(canvas, hours, baselineOffset, columnWidth)
            drawDigits(canvas, minutes, digitHeight + gap + baselineOffset, columnWidth)
        }

        private fun glyphBounds(digit: Char): Rect {
            paint.getTextBounds(digit.toString(), 0, 1, digitBounds)
            return digitBounds
        }

        /** Draws each digit centered by its glyph in its own column, so that both lines line up. */
        private fun drawDigits(
            canvas: Canvas,
            digits: String,
            baseline: Float,
            columnWidth: Float,
        ) {
            val firstCenter = (width - columnWidth * digits.length) / 2f + columnWidth / 2f
            digits.forEachIndexed { index, digit ->
                val bounds = glyphBounds(digit)
                val x = firstCenter + columnWidth * index - bounds.exactCenterX()
                canvas.drawText(digit.toString(), x, baseline, paint)
            }
        }
    }

    /** A lock in a circle, where the fingerprint sensor usually is. */
    private class LockIconView(context: Context) : View(context) {

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = context.getColor(ThemeR.color.settingslib_materialColorPrimaryContainer)
            }
        private val icon =
            context.getDrawable(R.drawable.ic_lock_fill_40px)?.mutate()?.apply {
                setTint(context.getColor(ThemeR.color.settingslib_materialColorOnPrimaryContainer))
            }

        override fun onDraw(canvas: Canvas) {
            val radius = min(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
            val half = (radius * LOCK_GLYPH_RATIO).toInt()
            icon?.setBounds(
                width / 2 - half,
                height / 2 - half,
                width / 2 + half,
                height / 2 + half,
            )
            icon?.draw(canvas)
        }
    }

    companion object {
        // Timing of a regular unlock, see LauncherUnlockAnimationController
        private const val UNLOCK_DURATION_MS = 633L
        private const val UNLOCK_DELAY_MS = 25L

        // SystemUI's KeyguardUnlockAnimationController
        private const val DEFAULT_UNLOCK_DURATION_MS = 300L
        private const val DEFAULT_UNLOCK_DELAY_MS = 67L
        private const val DEFAULT_UNLOCK_START_SCALE = 0.95f
        private const val DEFAULT_UNLOCK_START_TRANSLATION_Y = 0.05f
        private const val DEFAULT_UNLOCK_PIVOT_Y = 0.66f

        private const val RUN_MS = UNLOCK_DURATION_MS + UNLOCK_DELAY_MS
        private const val LOCKED_HOLD_MS = 800L
        private const val REST_HOLD_MS = 1800L
        private const val FADE_OUT_MS = 250L
        /** The lock screen fades out over this time, then the entrance starts. */
        private const val LOCK_EXIT_MS = 120f
        private const val ENTRANCE_START_MS = LOCKED_HOLD_MS + 120L
        private const val FADE_OUT_START_MS = ENTRANCE_START_MS + RUN_MS + REST_HOLD_MS
        private const val CYCLE_MS = FADE_OUT_START_MS + FADE_OUT_MS
        /** Time the lock screen takes to appear at the start of a run. */
        private const val LOCK_FADE_IN_MS = 250f

        private const val LOCK_SIZE_RATIO = 0.22f
        private const val LOCK_BOTTOM_RATIO = 0.16f
        private const val LOCK_GLYPH_RATIO = 0.55f

        // SystemUI's default lock screen clock, see DefaultClockProvider and the default axes in
        // FlexClockController
        private const val CLOCK_FONT_FAMILY = "google-sans-flex-clock"
        private const val CLOCK_FONT_VARIATIONS = "'wght' 500, 'wdth' 100, 'ROND' 100, 'slnt' 0"
        /** Where the area of the clock starts, below where the date would be. */
        private const val CLOCK_AREA_TOP_RATIO = 0.144f
        private const val CLOCK_HEIGHT_RATIO = 0.314f
        /** Gap between the hours and the minutes, relative to the height of the clock. */
        private const val CLOCK_LINE_GAP_RATIO = 0.04f
        private const val REFERENCE_TEXT_SIZE = 100f
        /** Space between the columns of digits, relative to their height. */
        private const val CLOCK_DIGIT_GAP_RATIO = 0.04f

        // The phone, relative to the width of its body or to its height
        private const val BODY_CORNER_RATIO = 0.11f
        private const val BEZEL_RATIO = 0.035f
        private const val BUTTON_OUTSET_RATIO = 0.025f
        // Power above volume, averaged over the Pixel 6 to 10 frames of Android Studio's device
        // art.
        // Phones differ, this only needs to read as a phone.
        private const val POWER_BUTTON_TOP_RATIO = 0.28f
        private const val POWER_BUTTON_LENGTH_RATIO = 0.065f
        private const val VOLUME_BUTTON_TOP_RATIO = 0.4f
        private const val VOLUME_BUTTON_LENGTH_RATIO = 0.13f
        private const val SIDE_PADDING_RATIO = 0.05f
        private const val STATUS_BAR_RATIO = 0.07f
        private const val NAV_BAR_RATIO = 0.04f
        private const val HOTSEAT_RATIO = 0.1f
        private const val HOTSEAT_GAP_RATIO = 0.03f
        private const val ICON_SIZE_RATIO = 0.62f
        private const val WIDGET_INSET_RATIO = 0.06f
        private const val WIDGET_CORNER_RATIO = 0.22f
    }
}

private enum class ItemKind {
    ICON,
    HOTSEAT,
    WIDGET,
}
