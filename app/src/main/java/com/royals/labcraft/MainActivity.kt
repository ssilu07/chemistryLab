package com.royals.labcraft

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bannerAdView: AdView
    private lateinit var bannerContainer: LinearLayout
    private lateinit var billingManager: BillingManager
    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading = false
    private var isMobileAdsInitialized = false
    private var hasLoadedBannerOnce = false
    private var hasCompletedFirstResume = false
    private var pendingInterstitialPreload = false
    private var interstitialRetryAttempt = 0
    private var lastNoFillElapsedMs = 0L
    private var interstitialRetryRunnable: Runnable? = null

    @Volatile
    private var isActivityResumed = false

    @Volatile
    private var isInterstitialAdReady = false

    @Volatile
    private var interstitialLoadedElapsedMs = 0L

    private val interstitialShowPending = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val MAX_NETWORK_RETRY_ATTEMPTS = 3
        private const val BASE_NETWORK_RETRY_DELAY_MS = 30_000L
        private const val NO_FILL_COOLDOWN_MS = 180_000L
        private const val INTERSTITIAL_EXPIRY_MS = 50L * 60L * 1000L
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finish() }
        }

        /** Call from JS: window.Android.isProUser() */
        @JavascriptInterface
        fun isProUser(): Boolean {
            return ::billingManager.isInitialized && billingManager.isPro
        }

        /** Call from JS: window.Android.getBillingStateJson() */
        @JavascriptInterface
        fun getBillingStateJson(): String {
            return buildBillingStateJson()
        }

        /** Call from JS: window.Android.purchasePro("monthly" | "lifetime") */
        @JavascriptInterface
        fun purchasePro(planType: String) {
            runOnUiThread {
                if (::billingManager.isInitialized) {
                    val normalized = if (planType == BillingManager.PLAN_LIFETIME) {
                        BillingManager.PLAN_LIFETIME
                    } else {
                        BillingManager.PLAN_MONTHLY
                    }
                    billingManager.launchPurchase(this@MainActivity, normalized)
                }
            }
        }

        /** Call from JS: window.Android.restorePurchases() */
        @JavascriptInterface
        fun restorePurchases() {
            runOnUiThread {
                if (::billingManager.isInitialized) {
                    billingManager.refreshPurchases(userInitiated = true)
                }
            }
        }

        /** Call from JS: window.Android.preloadInterstitialAd() — just-in-time preload */
        @JavascriptInterface
        fun preloadInterstitialAd() {
            if (::billingManager.isInitialized && billingManager.isPro) return
            runOnUiThread {
                loadInterstitialAd()
            }
        }

        /** Call from JS: window.Android.isInterstitialReady() */
        @JavascriptInterface
        fun isInterstitialReady(): Boolean {
            if (::billingManager.isInitialized && billingManager.isPro) return false
            if (!isInterstitialAdReady) return false
            val ageMs = SystemClock.elapsedRealtime() - interstitialLoadedElapsedMs
            return ageMs < INTERSTITIAL_EXPIRY_MS
        }

        /** Call from JS: window.Android.showInterstitialAd() — returns true only if an ad was ready to show */
        @JavascriptInterface
        fun showInterstitialAd(): Boolean {
            if (::billingManager.isInitialized && billingManager.isPro) return false
            val readyNow = isInterstitialReady() &&
                isActivityResumed &&
                !isFinishing &&
                !isDestroyed &&
                interstitialShowPending.compareAndSet(false, true)
            runOnUiThread {
                if (::billingManager.isInitialized && billingManager.isPro) {
                    interstitialShowPending.set(false)
                    return@runOnUiThread
                }
                val ad = interstitialAd
                if (ad != null && isActivityResumed && !isFinishing && !isDestroyed) {
                    val ageMs = SystemClock.elapsedRealtime() - interstitialLoadedElapsedMs
                    if (ageMs < INTERSTITIAL_EXPIRY_MS) {
                        isInterstitialAdReady = false
                        ad.show(this@MainActivity)
                        return@runOnUiThread
                    } else {
                        interstitialAd = null
                        isInterstitialAdReady = false
                        interstitialShowPending.set(false)
                    }
                } else {
                    interstitialShowPending.set(false)
                }
                if (interstitialAd == null) {
                    loadInterstitialAd()
                }
            }
            return readyNow
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

        /** Call from JS: window.Android.shareText(text, title) */
        @JavascriptInterface
        fun shareText(text: String, title: String? = null) {
            runOnUiThread {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        if (!title.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_SUBJECT, title)
                        }
                    }
                    val chooser = Intent.createChooser(intent, title ?: "Share via")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(chooser)
                } catch (_: Exception) {}
            }
        }

        /** Call from JS: window.Android.isNativeSharingSupported() */
        @JavascriptInterface
        fun isNativeSharingSupported(): Boolean = true

        /** Call from JS: window.Android.shareImage(base64Data, caption, title) */
        @JavascriptInterface
        fun shareImage(base64Data: String, caption: String? = null, title: String? = null) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    val imageBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val imagesDir = File(cacheDir, "images")
                    if (!imagesDir.exists()) {
                        imagesDir.mkdirs()
                    }

                    // Housekeeping: remove old cached share images if > 8 files
                    val existing = imagesDir.listFiles()
                    if (existing != null && existing.size > 8) {
                        existing.sortBy { it.lastModified() }
                        for (i in 0 until (existing.size - 5)) {
                            existing[i].delete()
                        }
                    }

                    val imageFile = File(imagesDir, "scientist_id_${System.currentTimeMillis()}.png")
                    FileOutputStream(imageFile).use { fos ->
                        fos.write(imageBytes)
                        fos.flush()
                    }

                    val authority = "${applicationContext.packageName}.fileprovider"
                    val contentUri = FileProvider.getUriForFile(this@MainActivity, authority, imageFile)

                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        if (!caption.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_TEXT, caption)
                        }
                        if (!title.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_SUBJECT, title)
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val chooser = Intent.createChooser(intent, title ?: "Share Mad Scientist ID")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(chooser)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to text share if image creation fails
                    if (!caption.isNullOrEmpty()) {
                        shareText(caption, title)
                    }
                }
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

        // Layout: WebView fills space, separated from adaptive banner by a divider & 12dp touch-safety buffer
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#060710"))
        }

        webView = WebView(this)
        container.addView(
            webView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        bannerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#060710"))
        }

        val bannerDivider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1E293B"))
        }
        bannerContainer.addView(
            bannerDivider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1).coerceAtLeast(1))
        )

        // Non-interactive 12dp safety buffer absorbs stray thumb touches below the WebView bottom nav
        val bannerSafetySpacer = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#060710"))
            isClickable = true
            isFocusable = false
        }
        bannerContainer.addView(
            bannerSafetySpacer,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(12))
        )

        bannerAdView = AdView(this).apply {
            adUnitId = BuildConfig.BANNER_AD_UNIT_ID
            setAdSize(getAdaptiveBannerAdSize())
            adListener = object : AdListener() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    // Rely on AdMob SDK's built-in refresh policy instead of manual retry storms
                }
            }
        }
        bannerContainer.addView(
            bannerAdView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(bannerContainer) { view, windowInsets ->
            val safeInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, 0, 0, safeInsets.bottom)
            windowInsets
        }

        container.addView(
            bannerContainer,
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

        billingManager = BillingManager(
            context = this,
            onEntitlementChanged = { isPro, _ ->
                applyProAdState(isPro)
                notifyWebBillingState()
            },
            onBillingEvent = { status, message ->
                notifyWebBillingEvent(status, message)
            }
        )
        applyProAdState(billingManager.isPro)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){if(typeof window.handleAndroidBack==='function'){try{window.handleAndroidBack();return true;}catch(e){return false;}}return false;})()"
                ) { result ->
                    if (result != "true") finish()
                }
            }
        })

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                notifyWebBillingState()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.setBackgroundColor(android.graphics.Color.parseColor("#060710"))
        webView.post {
            if (!isFinishing && !isDestroyed) {
                webView.loadUrl("file:///android_asset/index.html")
            }
        }

        // Delay Play Billing connect slightly so WebView gets initial main-thread focus without contention
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                billingManager.startConnection()
            }
        }, 1500L)

        // Initialize Google Mobile Ads off the main thread to avoid competing with WebView Babel startup
        Thread {
            MobileAds.initialize(this@MainActivity) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    isMobileAdsInitialized = true
                    if (!billingManager.isPro && isActivityResumed && !hasLoadedBannerOnce) {
                        hasLoadedBannerOnce = true
                        bannerAdView.loadAd(AdRequest.Builder().build())
                    }
                    if (!billingManager.isPro && pendingInterstitialPreload && isActivityResumed) {
                        pendingInterstitialPreload = false
                        loadInterstitialAd()
                    }
                }
            }
        }.start()
    }

    private fun buildBillingStateJson(): String {
        val isPro = ::billingManager.isInitialized && billingManager.isPro
        val activePlan = if (::billingManager.isInitialized) billingManager.activePlan else ""
        val monthlyPrice = if (::billingManager.isInitialized) billingManager.monthlyPriceText else BillingManager.DEFAULT_MONTHLY_PRICE
        val lifetimePrice = if (::billingManager.isInitialized) billingManager.lifetimePriceText else BillingManager.DEFAULT_LIFETIME_PRICE
        val storeReady = ::billingManager.isInitialized && billingManager.isStoreReady
        return JSONObject().apply {
            put("isPro", isPro)
            put("activePlan", activePlan)
            put("monthlyPrice", monthlyPrice)
            put("lifetimePrice", lifetimePrice)
            put("storeReady", storeReady)
            put("monthlyProductId", BuildConfig.PRO_MONTHLY_PRODUCT_ID)
            put("lifetimeProductId", BuildConfig.PRO_LIFETIME_PRODUCT_ID)
        }.toString()
    }

    private fun applyProAdState(isPro: Boolean) {
        if (!::bannerContainer.isInitialized || !::bannerAdView.isInitialized) return
        if (isPro) {
            bannerContainer.visibility = View.GONE
            bannerAdView.pause()
            interstitialAd = null
            isInterstitialAdReady = false
            interstitialShowPending.set(false)
            cancelPendingInterstitialRetry()
        } else {
            bannerContainer.visibility = View.VISIBLE
            if (isActivityResumed) {
                bannerAdView.resume()
                if (isMobileAdsInitialized && !hasLoadedBannerOnce) {
                    hasLoadedBannerOnce = true
                    bannerAdView.loadAd(AdRequest.Builder().build())
                }
            }
        }
    }

    private fun notifyWebBillingState() {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        val json = buildBillingStateJson()
        webView.evaluateJavascript(
            "if(typeof window.onLabCraftBillingUpdate==='function'){window.onLabCraftBillingUpdate($json);}",
            null
        )
    }

    private fun notifyWebBillingEvent(status: String, message: String) {
        if (!::webView.isInitialized || isFinishing || isDestroyed) return
        val payload = JSONObject().apply {
            put("status", status)
            put("message", message)
        }.toString()
        webView.evaluateJavascript(
            "if(typeof window.onLabCraftBillingEvent==='function'){window.onLabCraftBillingEvent($payload);}",
            null
        )
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun getAdaptiveBannerAdSize(): AdSize {
        val displayMetrics = resources.displayMetrics
        val adWidthPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            bounds.width() - insets.left - insets.right
        } else {
            displayMetrics.widthPixels
        }
        val adWidthDp = (adWidthPixels / displayMetrics.density).toInt().coerceAtLeast(320)
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp)
    }

    private fun cancelPendingInterstitialRetry() {
        interstitialRetryRunnable?.let { handler.removeCallbacks(it) }
        interstitialRetryRunnable = null
    }

    private fun loadInterstitialAd() {
        if (::billingManager.isInitialized && billingManager.isPro) return
        if (isFinishing || isDestroyed || !isActivityResumed) return
        if (!isMobileAdsInitialized) {
            pendingInterstitialPreload = true
            return
        }

        if (interstitialAd != null) {
            val ageMs = SystemClock.elapsedRealtime() - interstitialLoadedElapsedMs
            if (ageMs < INTERSTITIAL_EXPIRY_MS) return
            interstitialAd = null
            isInterstitialAdReady = false
        }
        if (isAdLoading) return

        // Avoid spamming NO_FILL regions (e.g., Russia) or permanently exhausted demand
        if (lastNoFillElapsedMs > 0L &&
            (SystemClock.elapsedRealtime() - lastNoFillElapsedMs) < NO_FILL_COOLDOWN_MS
        ) {
            return
        }

        cancelPendingInterstitialRetry()
        isAdLoading = true

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            BuildConfig.INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    isAdLoading = false
                    if (isFinishing || isDestroyed) {
                        interstitialAd = null
                        isInterstitialAdReady = false
                        return
                    }
                    interstitialAd = ad
                    interstitialShowPending.set(false)
                    isInterstitialAdReady = true
                    interstitialRetryAttempt = 0
                    lastNoFillElapsedMs = 0L
                    interstitialLoadedElapsedMs = SystemClock.elapsedRealtime()

                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            interstitialAd = null
                            isInterstitialAdReady = false
                            interstitialShowPending.set(false)
                            // Next preload is triggered just-in-time on the next user action
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                            interstitialAd = null
                            isInterstitialAdReady = false
                            interstitialShowPending.set(false)
                        }

                        override fun onAdShowedFullScreenContent() {
                            interstitialAd = null
                            isInterstitialAdReady = false
                            interstitialShowPending.set(false)
                        }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isInterstitialAdReady = false
                    isAdLoading = false

                    // Exponential backoff ONLY for transient network failures while foregrounded
                    if (error.code == AdRequest.ERROR_CODE_NETWORK_ERROR &&
                        interstitialRetryAttempt < MAX_NETWORK_RETRY_ATTEMPTS &&
                        isActivityResumed && !isFinishing && !isDestroyed
                    ) {
                        val delayMs = BASE_NETWORK_RETRY_DELAY_MS shl interstitialRetryAttempt
                        interstitialRetryAttempt++
                        cancelPendingInterstitialRetry()
                        val retry = Runnable {
                            interstitialRetryRunnable = null
                            if (isActivityResumed && !isFinishing && !isDestroyed) {
                                loadInterstitialAd()
                            }
                        }
                        interstitialRetryRunnable = retry
                        handler.postDelayed(retry, delayMs)
                    } else {
                        lastNoFillElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            }
        )
    }

    override fun onPause() {
        isActivityResumed = false
        cancelPendingInterstitialRetry()
        bannerAdView.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        val isPro = ::billingManager.isInitialized && billingManager.isPro
        if (isPro) {
            bannerContainer.visibility = View.GONE
            bannerAdView.pause()
        } else {
            bannerContainer.visibility = View.VISIBLE
            bannerAdView.resume()
            if (isMobileAdsInitialized) {
                if (!hasLoadedBannerOnce) {
                    hasLoadedBannerOnce = true
                    bannerAdView.loadAd(AdRequest.Builder().build())
                }
                if (pendingInterstitialPreload) {
                    pendingInterstitialPreload = false
                    loadInterstitialAd()
                }
            }
        }
        if (hasCompletedFirstResume && ::billingManager.isInitialized) {
            billingManager.refreshPurchases(userInitiated = false)
        }
        hasCompletedFirstResume = true
    }

    override fun onDestroy() {
        isActivityResumed = false
        cancelPendingInterstitialRetry()
        handler.removeCallbacksAndMessages(null)
        if (::billingManager.isInitialized) {
            billingManager.destroy()
        }
        bannerAdView.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
