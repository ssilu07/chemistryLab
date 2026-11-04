package com.royals.labcraft

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bannerAdView: AdView
    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false
    private val handler = Handler(Looper.getMainLooper())

    inner class WebAppInterface {
        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }

        /** Call from JS: window.Android.showInterstitialAd() — e.g., between levels */
        @JavascriptInterface
        fun showInterstitialAd() {
            runOnUiThread {
                if (interstitialAd != null) {
                    interstitialAd?.show(this@MainActivity)
                } else {
                    loadInterstitialAd()
                }
            }
        }

        /** Call from JS: window.Android.vibrate(ms) */
        @JavascriptInterface
        fun vibrate(milliseconds: Long) {
            runOnUiThread {
                try {
                    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val vibratorManager = getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                        vibratorManager?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(milliseconds.coerceIn(1L, 1000L), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(milliseconds.coerceIn(1L, 1000L))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        window.statusBarColor = android.graphics.Color.parseColor("#060710")
        window.navigationBarColor = android.graphics.Color.parseColor("#060710")

        // Layout: WebView fills space, banner pinned at bottom
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#060710"))
        }

        webView = WebView(this)
        container.addView(
            webView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        bannerAdView = AdView(this).apply {
            adUnitId = BuildConfig.BANNER_AD_UNIT_ID
            setAdSize(AdSize.BANNER)
        }
        container.addView(
            bannerAdView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(container)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.setBackgroundColor(android.graphics.Color.parseColor("#060710"))
        webView.loadUrl("file:///android_asset/index.html")

        MobileAds.initialize(this) {
            bannerAdView.loadAd(AdRequest.Builder().build())
            loadInterstitialAd()
        }
    }

    private fun loadInterstitialAd() {
        if (isAdLoading || interstitialAd != null) return
        isAdLoading = true

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            BuildConfig.INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isAdLoading = false

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            loadInterstitialAd()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            interstitialAd = null
                            loadInterstitialAd()
                        }

                        override fun onAdShowedFullScreenContent() {
                            interstitialAd = null
                        }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isAdLoading = false
                    // Retry after 15 seconds if failed (e.g. temporary network drop)
                    handler.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            loadInterstitialAd()
                        }
                    }, 15000)
                }
            }
        )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function(){if(typeof window.handleAndroidBack==='function'){try{window.handleAndroidBack();return true;}catch(e){return false;}}return false;})()"
        ) { result ->
            if (result != "true") finish()
        }
    }

    override fun onPause() {
        bannerAdView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        bannerAdView.resume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bannerAdView.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
