package com.bukovinafilip.gradey

import android.app.Application
import com.bukovinafilip.gradey.data.AndroidGradeyConfig
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

class GradeyApplication : Application() {
    val updates by lazy { com.bukovinafilip.gradey.updates.AppUpdateManager(this) }
    lateinit var graph: AndroidGradeyGraph
        private set

    lateinit var activeGraph: AndroidGradeyGraph
    fun createDemoGraph(): AndroidGradeyGraph = AndroidGradeyGraph.create(this, AndroidGradeyConfig("", "", "", "", useMockData = true))

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)

        if (BuildConfig.REVENUECAT_ANDROID_KEY.isNotBlank()) {
            Purchases.configure(PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_ANDROID_KEY).build())
        }

        graph = AndroidGradeyGraph.create(
            context = this,
            config = AndroidGradeyConfig(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                revenueCatAndroidKey = BuildConfig.REVENUECAT_ANDROID_KEY,
                useMockData = BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank(),
            ),
        )
        activeGraph = graph
        updates.start()
    }
}
