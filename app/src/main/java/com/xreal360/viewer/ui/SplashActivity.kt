package com.xreal360.viewer.ui

import android.annotation.SuppressLint
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.xreal360.viewer.R
import com.xreal360.viewer.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private val openMainRunnable = Runnable { openMainScreen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSplashVersion.text = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            getString(R.string.version_format, pInfo.versionName)
        } catch (_: Exception) {
            getString(R.string.version_fallback)
        }

        prepareIntroAnimation()
        startIntroAnimation()
        handler.postDelayed(openMainRunnable, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMainRunnable)
        super.onDestroy()
    }

    private fun prepareIntroAnimation() {
        binding.imgSplashLogo.alpha = 0f
        binding.imgSplashLogo.scaleX = 0.92f
        binding.imgSplashLogo.scaleY = 0.92f
        binding.tvSplashCredit.alpha = 1f
        binding.tvSplashVersion.alpha = 1f
    }

    private fun startIntroAnimation() {
        val logoFade = ObjectAnimator.ofFloat(binding.imgSplashLogo, "alpha", 0f, 1f)
        val logoScaleX = ObjectAnimator.ofFloat(binding.imgSplashLogo, "scaleX", 0.9f, 1f)
        val logoScaleY = ObjectAnimator.ofFloat(binding.imgSplashLogo, "scaleY", 0.9f, 1f)

        AnimatorSet().apply {
            playTogether(
                logoFade,
                logoScaleX,
                logoScaleY
            )
            duration = 780L
            interpolator = DecelerateInterpolator(1.8f)
            start()
        }
    }

    private fun openMainScreen() {
        val next = Intent(this, MainActivity::class.java).apply {
            intent?.extras?.let { putExtras(it) }
        }
        startActivity(next)
        finish()
    }

    companion object {
        private const val SPLASH_DURATION_MS = 1700L
    }
}
