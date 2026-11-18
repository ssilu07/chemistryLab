package com.royals.labcraft

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Manages Google Play Billing entitlements for Lab Craft PRO:
 * - Monthly subscription (BuildConfig.PRO_MONTHLY_PRODUCT_ID, default display ₹199/month)
 * - Lifetime one-time purchase (BuildConfig.PRO_LIFETIME_PRODUCT_ID, default display ₹999)
 */
class BillingManager(
    context: Context,
    private val onEntitlementChanged: (isPro: Boolean, plan: String) -> Unit,
    private val onBillingEvent: (status: String, message: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isPro: Boolean = prefs.getBoolean(KEY_IS_PRO, false)
        private set

    @Volatile
    var activePlan: String = prefs.getString(KEY_PRO_PLAN, "") ?: ""
        private set

    @Volatile
    var monthlyPriceText: String = DEFAULT_MONTHLY_PRICE
        private set

    @Volatile
    var lifetimePriceText: String = DEFAULT_LIFETIME_PRICE
        private set

    @Volatile
    var isStoreReady: Boolean = false
        private set

    private var monthlyProductDetails: ProductDetails? = null
    private var lifetimeProductDetails: ProductDetails? = null
    private var isConnecting = false
    private var lastSetupFailureElapsedMs = 0L

    companion object {
        private const val PREFS_NAME = "labcraft_billing_prefs"
        private const val KEY_IS_PRO = "is_pro_entitled"
        private const val KEY_PRO_PLAN = "pro_plan_type"
        private const val SETUP_RETRY_COOLDOWN_MS = 120_000L

        const val PLAN_MONTHLY = "monthly"
        const val PLAN_LIFETIME = "lifetime"
        const val DEFAULT_MONTHLY_PRICE = "₹199"
        const val DEFAULT_LIFETIME_PRICE = "₹999"
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) {
                    processIncomingPurchases(purchases, notifySuccess = true)
                }
            }
            BillingResponseCode.USER_CANCELED -> {
                postEvent("canceled", "Purchase cancelled.")
            }
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refreshPurchases(userInitiated = true)
            }
            BillingResponseCode.BILLING_UNAVAILABLE -> {
                postEvent("unavailable", "Google Play Billing is unavailable on this device or region.")
            }
            else -> {
                val msg = billingResult.debugMessage.takeIf { it.isNotBlank() }
                    ?: "Purchase could not be completed (code ${billingResult.responseCode})."
                postEvent("error", msg)
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun startConnection(forceRetry: Boolean = false) {
        if (billingClient.isReady) {
            isStoreReady = true
            queryProducts()
            refreshPurchases(userInitiated = false)
            return
        }
        if (isConnecting) return
        if (!forceRetry && lastSetupFailureElapsedMs > 0L &&
            (SystemClock.elapsedRealtime() - lastSetupFailureElapsedMs) < SETUP_RETRY_COOLDOWN_MS
        ) {
            return
        }
        isConnecting = true

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                isConnecting = false
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    isStoreReady = true
                    lastSetupFailureElapsedMs = 0L
                    queryProducts()
                    refreshPurchases(userInitiated = false)
                } else {
                    isStoreReady = false
                    lastSetupFailureElapsedMs = SystemClock.elapsedRealtime()
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnecting = false
                isStoreReady = false
            }
        })
    }

    fun queryProducts(onReady: (() -> Unit)? = null) {
        if (!billingClient.isReady) {
            startConnection()
            return
        }

        val subsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BuildConfig.PRO_MONTHLY_PRODUCT_ID)
                        .setProductType(ProductType.SUBS)
                        .build()
                )
            )
            .build()

        val inappParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(BuildConfig.PRO_LIFETIME_PRODUCT_ID)
                        .setProductType(ProductType.INAPP)
                        .build()
                )
            )
            .build()

        val queryLock = Any()
        var subsDone = false
        var inappDone = false
        fun markDone(isSubs: Boolean) {
            val allDone: Boolean
            synchronized(queryLock) {
                if (isSubs) subsDone = true else inappDone = true
                allDone = subsDone && inappDone
            }
            if (allDone) {
                mainHandler.post {
                    onEntitlementChanged(isPro, activePlan)
                    onReady?.invoke()
                }
            }
        }

        billingClient.queryProductDetailsAsync(subsParams) { result, productDetailsResult ->
            if (result.responseCode == BillingResponseCode.OK) {
                val details = productDetailsResult.productDetailsList.firstOrNull {
                    it.productId == BuildConfig.PRO_MONTHLY_PRODUCT_ID
                }
                if (details != null) {
                    monthlyProductDetails = details
                    val phase = details.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.let { it.lastOrNull() ?: it.firstOrNull() }
                    if (phase != null && phase.formattedPrice.isNotBlank()) {
                        monthlyPriceText = phase.formattedPrice
                    }
                }
            }
            markDone(isSubs = true)
        }

        billingClient.queryProductDetailsAsync(inappParams) { result, productDetailsResult ->
            if (result.responseCode == BillingResponseCode.OK) {
                val details = productDetailsResult.productDetailsList.firstOrNull {
                    it.productId == BuildConfig.PRO_LIFETIME_PRODUCT_ID
                }
                if (details != null) {
                    lifetimeProductDetails = details
                    val oneTimePrice = details.oneTimePurchaseOfferDetailsList
                        ?.firstOrNull()
                        ?.formattedPrice
                        ?: details.oneTimePurchaseOfferDetails?.formattedPrice
                    if (!oneTimePrice.isNullOrBlank()) {
                        lifetimePriceText = oneTimePrice
                    }
                }
            }
            markDone(isSubs = false)
        }
    }

    fun refreshPurchases(userInitiated: Boolean) {
        if (!billingClient.isReady) {
            if (userInitiated) {
                postEvent("error", "Connecting to Google Play Store. Please try again in a moment.")
                startConnection(forceRetry = true)
            } else {
                startConnection(forceRetry = false)
            }
            return
        }

        val syncLock = Any()
        var subsOk = false
        var inappOk = false
        var subsDone = false
        var inappDone = false
        val activePurchases = mutableListOf<Purchase>()

        fun handleQueryResult(isSubs: Boolean, ok: Boolean, list: List<Purchase>) {
            val allDone: Boolean
            val snapshot: List<Purchase>
            val bothOk: Boolean
            synchronized(syncLock) {
                if (ok) {
                    if (isSubs) subsOk = true else inappOk = true
                    activePurchases.addAll(list)
                }
                if (isSubs) subsDone = true else inappDone = true
                allDone = subsDone && inappDone
                snapshot = if (allDone) activePurchases.toList() else emptyList()
                bothOk = subsOk && inappOk
            }
            if (!allDone) return

            if (snapshot.isNotEmpty()) {
                processIncomingPurchases(snapshot, notifySuccess = userInitiated)
            } else if (bothOk) {
                val wasPro = isPro
                updateStoredEntitlement(false, "")
                if (wasPro) {
                    mainHandler.post { onEntitlementChanged(false, "") }
                }
                if (userInitiated) {
                    postEvent("restore_empty", "No active Lab Craft PRO subscription or lifetime purchase found on this account.")
                }
            } else if (userInitiated) {
                postEvent("error", "Unable to verify purchases with Google Play right now.")
            }
        }

        val subsQuery = QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(subsQuery) { billingResult, purchasesList ->
            handleQueryResult(
                isSubs = true,
                ok = billingResult.responseCode == BillingResponseCode.OK,
                list = purchasesList
            )
        }

        val inappQuery = QueryPurchasesParams.newBuilder()
            .setProductType(ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(inappQuery) { billingResult, purchasesList ->
            handleQueryResult(
                isSubs = false,
                ok = billingResult.responseCode == BillingResponseCode.OK,
                list = purchasesList
            )
        }
    }

    fun launchPurchase(activity: Activity, planType: String) {
        if (!billingClient.isReady) {
            postEvent("error", "Google Play Store is not ready on this device right now.")
            startConnection(forceRetry = true)
            return
        }

        if (planType == PLAN_MONTHLY) {
            val subFeature = billingClient.isFeatureSupported(FeatureType.SUBSCRIPTIONS)
            if (subFeature.responseCode != BillingResponseCode.OK) {
                postEvent("unavailable", "Subscriptions are not supported by the Play Store version on this device.")
                return
            }
            val details = monthlyProductDetails
            if (details == null) {
                queryProducts {
                    val refreshed = monthlyProductDetails
                    if (refreshed != null) {
                        launchSubscriptionFlow(activity, refreshed)
                    } else {
                        postEvent(
                            "unavailable",
                            "Monthly plan (${BuildConfig.PRO_MONTHLY_PRODUCT_ID}) is not live in Play Console yet."
                        )
                    }
                }
                return
            }
            launchSubscriptionFlow(activity, details)
        } else {
            val details = lifetimeProductDetails
            if (details == null) {
                queryProducts {
                    val refreshed = lifetimeProductDetails
                    if (refreshed != null) {
                        launchLifetimeFlow(activity, refreshed)
                    } else {
                        postEvent(
                            "unavailable",
                            "Lifetime pass (${BuildConfig.PRO_LIFETIME_PRODUCT_ID}) is not live in Play Console yet."
                        )
                    }
                }
                return
            }
            launchLifetimeFlow(activity, details)
        }
    }

    private fun launchSubscriptionFlow(activity: Activity, details: ProductDetails) {
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken.isNullOrBlank()) {
            postEvent("unavailable", "No active subscription offer found for ${details.productId}.")
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                val res = billingClient.launchBillingFlow(activity, flowParams)
                if (res.responseCode != BillingResponseCode.OK) {
                    postEvent("error", "Could not open Google Play checkout (code ${res.responseCode}).")
                }
            }
        }
    }

    private fun launchLifetimeFlow(activity: Activity, details: ProductDetails) {
        val builder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        val offerToken = details.oneTimePurchaseOfferDetailsList
            ?.firstOrNull()
            ?.offerToken
        if (!offerToken.isNullOrBlank()) {
            builder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(builder.build()))
            .build()

        mainHandler.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                val res = billingClient.launchBillingFlow(activity, flowParams)
                if (res.responseCode != BillingResponseCode.OK) {
                    postEvent("error", "Could not open Google Play checkout (code ${res.responseCode}).")
                }
            }
        }
    }

    private fun processIncomingPurchases(purchases: List<Purchase>, notifySuccess: Boolean) {
        var entitled = false
        var resolvedPlan = ""
        var hasPending = false

        for (purchase in purchases) {
            val isLifetime = purchase.products.contains(BuildConfig.PRO_LIFETIME_PRODUCT_ID)
            val isMonthly = purchase.products.contains(BuildConfig.PRO_MONTHLY_PRODUCT_ID)
            if (!isLifetime && !isMonthly) continue

            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    entitled = true
                    if (isLifetime) {
                        resolvedPlan = PLAN_LIFETIME
                    } else if (resolvedPlan.isEmpty()) {
                        resolvedPlan = PLAN_MONTHLY
                    }

                    if (!purchase.isAcknowledged) {
                        val ackParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                        billingClient.acknowledgePurchase(ackParams) { _ ->
                            // Acknowledged with Google Play so purchase is not auto-refunded after 3 days
                        }
                    }
                }
                Purchase.PurchaseState.PENDING -> {
                    hasPending = true
                }
            }
        }

        if (entitled) {
            updateStoredEntitlement(true, resolvedPlan)
            mainHandler.post {
                onEntitlementChanged(true, resolvedPlan)
            }
            if (notifySuccess) {
                val label = if (resolvedPlan == PLAN_LIFETIME) "Lifetime Pass" else "Monthly PRO"
                postEvent("purchased", "Lab Craft PRO ($label) unlocked! Ads removed.")
            }
        } else if (hasPending) {
            postEvent("pending", "Payment is pending confirmation. PRO will activate automatically once completed.")
        }
    }

    private fun updateStoredEntitlement(entitled: Boolean, plan: String) {
        isPro = entitled
        activePlan = plan
        prefs.edit()
            .putBoolean(KEY_IS_PRO, entitled)
            .putString(KEY_PRO_PLAN, plan)
            .apply()
    }

    private fun postEvent(status: String, message: String) {
        mainHandler.post {
            onBillingEvent(status, message)
        }
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
