package com.samsung.screenshoterjetpackcompose

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.apply
import kotlin.math.abs

object FloatingCaptureOverlay {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    fun show(
        context: Context,
        onCapture: () -> Unit
    ) {
        if (overlayView != null) return

        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val inflater = LayoutInflater.from(appContext)
        val view = inflater.inflate(R.layout.overlay_capture_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        layoutParams = params
        val captureButton = view.findViewById<View>(R.id.overlayCaptureButton).apply {
            setOnClickListener { onCapture() }
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var hasMoved = false
            private val touchSlop = appContext.resources.displayMetrics.density * 4

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp = layoutParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x
                        initialY = lp.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        hasMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (!hasMoved && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                            hasMoved = true
                        }
                        if (hasMoved) {
                            lp.x = (initialX + deltaX).toInt()
                            lp.y = (initialY + deltaY).toInt()
                            windowManager?.updateViewLayout(overlayView, lp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!hasMoved) {
                            captureButton.performClick()
                        }
                        return true
                    }
                }
                return true
            }
        })

        wm.addView(view, params)
        overlayView = view
    }

    fun hide(context: Context) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        try {
            wm.removeView(view)
        } catch (_: IllegalArgumentException) {
        }
        overlayView = null
        layoutParams = null
        windowManager = null
    }
}


