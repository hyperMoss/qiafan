package com.example.qiafan.fanfou

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import kotlin.math.min

/** Image preview with a fit-to-screen baseline and double-tap zoom. */
internal class ZoomableImageView(context: Context) : ImageView(context) {
    private val transform = Matrix()
    private var zoomed = false
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (drawable == null) return true
            if (zoomed) {
                resetTransform()
            } else {
                transform.postScale(2.5f, 2.5f, event.x - paddingLeft, event.y - paddingTop)
                zoomed = true
                constrainTransform()
                imageMatrix = transform
            }
            return true
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!zoomed || drawable == null) return false
            transform.postTranslate(-distanceX, -distanceY)
            constrainTransform()
            imageMatrix = transform
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // Glide can replace the drawable after this view has been laid out.
        post { resetTransform() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun resetTransform() {
        val image = drawable ?: return
        val availableWidth = width - paddingLeft - paddingRight
        val availableHeight = height - paddingTop - paddingBottom
        if (availableWidth <= 0 || availableHeight <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) return
        val scale = min(availableWidth.toFloat() / image.intrinsicWidth, availableHeight.toFloat() / image.intrinsicHeight)
        transform.setScale(scale, scale)
        transform.postTranslate(
            (availableWidth - image.intrinsicWidth * scale) / 2f,
            (availableHeight - image.intrinsicHeight * scale) / 2f
        )
        zoomed = false
        imageMatrix = transform
    }

    private fun constrainTransform() {
        val image = drawable ?: return
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val bounds = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        transform.mapRect(bounds)
        fun correction(start: Float, end: Float, size: Float): Float = when {
            end - start <= size -> (size - end - start) / 2f
            start > 0f -> -start
            end < size -> size - end
            else -> 0f
        }
        transform.postTranslate(correction(bounds.left, bounds.right, availableWidth), correction(bounds.top, bounds.bottom, availableHeight))
    }
}
