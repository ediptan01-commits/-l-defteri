
package com.olcudefteri.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Play Billing Manager.
 * Tek seferlik Pro satın alma ve abonelik işlemlerini yönetir.
 */
public final class BillingManager {

    public static final String ONE_TIME_PRODUCT_ID =
            "olcu_defteri_pro";

    public static final String SUBS_PRODUCT_ID =
            "olcu_defteri_pro_abonelik";

    public static final String MONTHLY_BASE_PLAN_ID =
            "monthly";

    public static final String YEARLY_BASE_PLAN_ID =
            "yearly";

    private static final String PREFS =
            "olcu_defteri_billing";

    private static final String KEY_PRO =
            "pro_unlocked";

    private static final String KEY_ONE_TIME_PRO =
            "one_time_pro";

    public interface Callback {
        void onReady(String price);

        void onSubscriptionReady(
                String monthlyPrice,
                String yearlyPrice
        );

        void onEntitlement(boolean pro);

        void onError(String message);
    }

    private final Activity activity;
    private final Callback callback;
    private final SharedPreferences prefs;
    private final Handler main =
            new Handler(Looper.getMainLooper());

    private BillingClient client;

    private ProductDetails oneTimeDetails;
    private ProductDetails subscriptionDetails;

    private boolean connected = false;

    public BillingManager(
            Activity activity,
            Callback callback
    ) {
        this.activity = activity;
        this.callback = callback;

        prefs = activity.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );

        buildClient();
    }

    private void buildClient() {
        client = BillingClient.newBuilder(activity)
                .setListener((result, purchases) -> {
                    if (result.getResponseCode()
                            == BillingClient.BillingResponseCode.OK
                            && purchases != null) {

                        processPurchases(purchases);

                    } else if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.USER_CANCELED) {

                        error(
                                "Satın alma hatası: "
                                + result.getResponseCode()
                                + " - "
                                + result.getDebugMessage()
                        );
                    }
                })
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .enableAutoServiceReconnection()
                .build();

        connect();
    }

    private void connect() {
        client.startConnection(
                new BillingClientStateListener() {

                    @Override
                    public void onBillingSetupFinished(
                            BillingResult result
                    ) {
                        if (result.getResponseCode()
                                != BillingClient.BillingResponseCode.OK) {

                            connected = false;

                            error(
                                    "Google Play bağlantı hatası: "
                                    + result.getResponseCode()
                                    + " - "
                                    + result.getDebugMessage()
                            );

                            callback.onEntitlement(
                                    prefs.getBoolean(KEY_PRO, false)
                            );

                            return;
                        }

                        connected = true;

                        queryProducts();
                        restoreInternal();
                    }

                    @Override
                    public void onBillingServiceDisconnected() {
                        connected = false;

                        error(
                                "Google Play bağlantısı kesildi. "
                                + "Lütfen tekrar deneyin."
                        );
                    }
                }
        );
    }

    private void queryProducts() {
        List<QueryProductDetailsParams.Product> products =
                new ArrayList<>();

        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(ONE_TIME_PRODUCT_ID)
                        .setProductType(
                                BillingClient.ProductType.INAPP
                        )
                        .build()
        );

        products.add(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBS_PRODUCT_ID)
                        .setProductType(
                                BillingClient.ProductType.SUBS
                        )
                        .build()
        );

        QueryProductDetailsParams params =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(products)
                        .build();

        client.queryProductDetailsAsync(
                params,
                (result, detailsResult) -> {

                    oneTimeDetails = null;
                    subscriptionDetails = null;

                    if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {

                        callback.onReady("");
                        callback.onSubscriptionReady("", "");

                        error(
                                "Ürün sorgulama hatası: "
                                + result.getResponseCode()
                                + " - "
                                + result.getDebugMessage()
                        );

                        return;
                    }

                    List<ProductDetails> details =
                            detailsResult.getProductDetailsList();

                    for (ProductDetails product : details) {

                        if (ONE_TIME_PRODUCT_ID.equals(
                                product.getProductId()
                        )) {
                            oneTimeDetails = product;

                            String price = "";

                            List<ProductDetails.OneTimePurchaseOfferDetails>
                                    offers =
                                    product.getOneTimePurchaseOfferDetailsList();

                            if (offers != null && !offers.isEmpty()) {
                                price = offers.get(0).getFormattedPrice();
                            }

                            callback.onReady(price);

                        } else if (SUBS_PRODUCT_ID.equals(
                                product.getProductId()
                        )) {
                            subscriptionDetails = product;

                            String monthly = priceForBasePlan(
                                    product,
                                    MONTHLY_BASE_PLAN_ID
                            );

                            String yearly = priceForBasePlan(
                                    product,
                                    YEARLY_BASE_PLAN_ID
                            );

                            callback.onSubscriptionReady(
                                    monthly,
                                    yearly
                            );
                        }
                    }

                    if (oneTimeDetails == null) {
                        callback.onReady("");

                        error(
                                "Tek seferlik Pro ürünü bulunamadı. "
                                + "Ürün kimliği: "
                                + ONE_TIME_PRODUCT_ID
                        );
                    }

                    if (subscriptionDetails == null) {
                        callback.onSubscriptionReady("", "");

                        error(
                                "Abonelik ürünü Google Play'den alınamadı. "
                                + "Ürün kimliği: "
                                + SUBS_PRODUCT_ID
                                + ". Play Console ürün durumunu, "
                                + "uygulama paketini ve Play Store "
                                + "hesabını kontrol edin."
                        );

                    } else if (priceForBasePlan(
                            subscriptionDetails,
                            MONTHLY_BASE_PLAN_ID
                    ).isEmpty()) {

                        error(
                                "Abonelik ürünü bulundu ancak monthly "
                                + "planının fiyatı alınamadı. "
                                + "Play Console'daki planı kontrol edin."
                        );
                    }
                }
        );
    }

    private String priceForBasePlan(
            ProductDetails product,
            String basePlanId
    ) {
        List<ProductDetails.SubscriptionOfferDetails> offers =
                product.getSubscriptionOfferDetails();

        if (offers == null) {
            return "";
        }

        for (ProductDetails.SubscriptionOfferDetails offer : offers) {

            if (!basePlanId.equals(offer.getBasePlanId())) {
                continue;
            }

            List<ProductDetails.PricingPhase> phases =
                    offer.getPricingPhases()
                            .getPricingPhaseList();

            if (phases != null && !phases.isEmpty()) {
                return phases.get(
                        phases.size() - 1
                ).getFormattedPrice();
            }
        }

        return "";
    }

    private ProductDetails.SubscriptionOfferDetails offerFor(
            String basePlanId
    ) {
        if (subscriptionDetails == null
                || subscriptionDetails.getSubscriptionOfferDetails() == null) {
            return null;
        }

        for (ProductDetails.SubscriptionOfferDetails offer
                : subscriptionDetails.getSubscriptionOfferDetails()) {

            if (basePlanId.equals(offer.getBasePlanId())) {
                return offer;
            }
        }

        return null;
    }

    public void buyPro() {
        if (!connected || oneTimeDetails == null) {
            error("Tek seferlik Pro ürünü hazır değil.");
            return;
        }

        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                oneTimeDetails.getOneTimePurchaseOfferDetailsList();

        if (offers == null || offers.isEmpty()) {
            error("Pro satın alma teklifi bulunamadı.");
            return;
        }

        launch(
                oneTimeDetails,
                offers.get(0).getOfferToken()
        );
    }

    public void buyMonthly() {
        buySubscription(MONTHLY_BASE_PLAN_ID);
    }

    public void buyYearly() {
        buySubscription(YEARLY_BASE_PLAN_ID);
    }

    private void buySubscription(String basePlanId) {
        if (!connected) {
            error("Google Play bağlantısı hazır değil.");
            return;
        }

        if (subscriptionDetails == null) {
            error(
                    "Abonelik ürünü henüz yüklenmedi. "
                    + "Pro Merkezi açılırken gösterilen ürün "
                    + "sorgulama hatasını kontrol edin."
            );

            queryProducts();
            return;
        }

        ProductDetails.SubscriptionOfferDetails offer =
                offerFor(basePlanId);

        if (offer == null) {
            error(
                    "Google Play ürününde şu plan bulunamadı: "
                    + basePlanId
            );
            return;
        }

        launch(
                subscriptionDetails,
                offer.getOfferToken()
        );
    }

    private void launch(
            ProductDetails product,
            String offerToken
    ) {
        List<BillingFlowParams.ProductDetailsParams> params =
                new ArrayList<>();

        params.add(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .setOfferToken(offerToken)
                        .build()
        );

        BillingResult result = client.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(params)
                        .build()
        );

        if (result.getResponseCode()
                != BillingClient.BillingResponseCode.OK) {

            error(
                    "Satın alma ekranı açılamadı: "
                    + result.getResponseCode()
                    + " - "
                    + result.getDebugMessage()
            );
        }
    }

    public void restore() {
        if (!connected) {
            error("Google Play bağlantısı henüz hazır değil.");
            return;
        }

        restoreInternal();
    }

    private void restoreInternal() {
        QueryPurchasesParams inapp =
                QueryPurchasesParams.newBuilder()
                        .setProductType(
                                BillingClient.ProductType.INAPP
                        )
                        .build();

        client.queryPurchasesAsync(
                inapp,
                (result, purchases) -> {
                    if (result.getResponseCode()
                            == BillingClient.BillingResponseCode.OK) {

                        processPurchases(purchases);

                    } else {
                        error(
                                "Tek seferlik satın almalar kontrol "
                                + "edilemedi: "
                                + result.getDebugMessage()
                        );
                    }
                }
        );

        QueryPurchasesParams subs =
                QueryPurchasesParams.newBuilder()
                        .setProductType(
                                BillingClient.ProductType.SUBS
                        )
                        .build();

        client.queryPurchasesAsync(
                subs,
                (result, purchases) -> {
                    if (result.getResponseCode()
                            == BillingClient.BillingResponseCode.OK) {

                        processSubscriptionPurchases(purchases);

                    } else {
                        error(
                                "Abonelikler kontrol edilemedi: "
                                + result.getDebugMessage()
                        );
                    }
                }
        );
    }

    private void processPurchases(List<Purchase> purchases) {
        boolean oneTimePro = false;

        for (Purchase purchase : purchases) {

            if (purchase.getPurchaseState()
                    != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            if (!purchase.getProducts().contains(
                    ONE_TIME_PRODUCT_ID
            )) {
                continue;
            }

            oneTimePro = true;
            acknowledge(purchase);
        }

        if (oneTimePro) {
            prefs.edit()
                    .putBoolean(KEY_ONE_TIME_PRO, true)
                    .putBoolean(KEY_PRO, true)
                    .apply();
        }

        callback.onEntitlement(
                prefs.getBoolean(KEY_PRO, false)
        );
    }

    private void processSubscriptionPurchases(
            List<Purchase> purchases
    ) {
        boolean activeSubscription = false;

        for (Purchase purchase : purchases) {

            if (purchase.getPurchaseState()
                    != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            if (!purchase.getProducts().contains(
                    SUBS_PRODUCT_ID
            )) {
                continue;
            }

            activeSubscription = true;
            acknowledge(purchase);
        }

        if (activeSubscription) {
            prefs.edit()
                    .putBoolean(KEY_PRO, true)
                    .apply();

        } else if (!hasOneTimePurchaseCached()) {
            prefs.edit()
                    .putBoolean(KEY_PRO, false)
                    .apply();
        }

        callback.onEntitlement(
                prefs.getBoolean(KEY_PRO, false)
        );
    }

    private boolean hasOneTimePurchaseCached() {
        return prefs.getBoolean(KEY_ONE_TIME_PRO, false);
    }

    private void acknowledge(Purchase purchase) {
        if (purchase.isAcknowledged()) {
            return;
        }

        client.acknowledgePurchase(
                com.android.billingclient.api.AcknowledgePurchaseParams
                        .newBuilder()
                        .setPurchaseToken(
                                purchase.getPurchaseToken()
                        )
                        .build(),
                result -> {
                    if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {

                        error(
                                "Satın alma onaylanamadı: "
                                + result.getResponseCode()
                                + " - "
                                + result.getDebugMessage()
                        );
                    }
                }
        );
    }

    public void manageSubscriptions() {
        try {
            Intent intent = new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                            "https://play.google.com/store/account/subscriptions"
                    )
            );

            activity.startActivity(intent);

        } catch (Exception e) {
            error("Abonelik yönetimi açılamadı.");
        }
    }

    public String statusJson() {
        try {
            JSONObject json = new JSONObject();

            json.put("productId", ONE_TIME_PRODUCT_ID);
            json.put("subscriptionProductId", SUBS_PRODUCT_ID);
            json.put("pro", prefs.getBoolean(KEY_PRO, false));
            json.put("connected", connected);
            json.put(
                    "ready",
                    oneTimeDetails != null || subscriptionDetails != null
            );

            return json.toString();

        } catch (Exception e) {
            return "{\"pro\":false,\"connected\":false,\"ready\":false}";
        }
    }

    public void destroy() {
        try {
            if (client != null) {
                client.endConnection();
            }
        } catch (Exception ignored) {
        }
    }

    private void error(String message) {
        final String safe =
                (message == null || message.trim().isEmpty())
                        ? "Google Play işlemi tamamlanamadı."
                        : message;

        main.post(() -> callback.onError(safe));
    }
}
