package com.callmanager.callmanager

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout

class EdgeGlowService : Service() {

    private var windowManager: WindowManager? = null
    private var glowView: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showGlow()
        
        // Reset timer if already showing
        handler.removeCallbacksAndMessages(null)
        // Auto-dismiss after 6 seconds
        handler.postDelayed({ stopSelf() }, 6000)
        
        return START_NOT_STICKY
    }

    private fun showGlow() {
        if (glowView != null) {
            // Restart animation if already visible
            val border = glowView?.findViewById<View>(R.id.glow_border)
            startPulsingAnimation(border)
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        
        // Inflate the full screen layout
        glowView = inflater.inflate(R.layout.layout_edge_glow, FrameLayout(this), false)

        val layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
            
            // Ensure it covers notches and system bars
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager?.addView(glowView, layoutParams)
            
            val border = glowView?.findViewById<View>(R.id.glow_border)
            startPulsingAnimation(border)
            
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun startPulsingAnimation(view: View?) {
        view?.clearAnimation()
        val anim = AlphaAnimation(0.4f, 1.0f).apply {
            duration = 500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        view?.startAnimation(anim)
    }

    override fun onDestroy() {
        super.onDestroy()
        glowView?.let {
            it.clearAnimation()
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        glowView = null
        handler.removeCallbacksAndMessages(null)
    }
}
