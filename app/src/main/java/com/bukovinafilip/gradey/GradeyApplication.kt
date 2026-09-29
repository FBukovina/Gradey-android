package com.bukovinafilip.gradey

import android.app.Application
import com.bukovinafilip.gradey.data.AndroidGradeyConfig
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

class GradeyApplication : Application() {
    val updates by lazy { com.bukovinafilip.gradey.updates.AppUpdateManager(this) }
    val absenceOverrides by lazy { com.bukovinafilip.gradey.data.AndroidAbsenceOverrideRepository(this) }
    val demoAbsenceOverrides by lazy { com.bukovinafilip.gradey.data.InMemoryAbsenceOverrideRepository() }
    val personalPlanner by lazy { com.bukovinafilip.gradey.data.AndroidPersonalPlannerRepository(this) }
    val plannerCalendarMirror by lazy { com.bukovinafilip.gradey.domain.PlannerCalendarMirror(
        personalPlanner, com.bukovinafilip.gradey.data.AndroidPlannerCalendarStateStore(this),
        com.bukovinafilip.gradey.data.AndroidPlannerCalendarAdapter(this),
    ) }
    val demoPlanner by lazy { com.bukovinafilip.gradey.data.InMemoryPersonalPlannerRepository() }
    lateinit var graph: AndroidGradeyGraph
        private set

    lateinit var activeGraph: AndroidGradeyGraph
    val localGraph: AndroidGradeyGraph by lazy {
        if (!graph.isDemo) graph else AndroidGradeyGraph.create(this, AndroidGradeyConfig("", "", "", "", useMockData = false))
    }
    fun createDemoGraph(): AndroidGradeyGraph = AndroidGradeyGraph.create(this, AndroidGradeyConfig("", "", "", "", useMockData = true))

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        com.bukovinafilip.gradey.feature.support.GradeySupport.initialize(this,
            com.bukovinafilip.gradey.feature.support.SupportConfiguration.from(BuildConfig.INTERCOM_ANDROID_API_KEY, BuildConfig.INTERCOM_APP_ID))

        if (BuildConfig.REVENUECAT_ANDROID_KEY.isNotBlank()) {
            Purchases.configure(PurchasesConfiguration.Builder(this, BuildConfig.REVENUECAT_ANDROID_KEY).build())
        }

        graph = AndroidGradeyGraph.create(
            context = this,
            config = AndroidGradeyConfig(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
                appleOAuthClientId = BuildConfig.APPLE_OAUTH_CLIENT_ID,
                revenueCatAndroidKey = BuildConfig.REVENUECAT_ANDROID_KEY,
                useMockData = BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank(),
            ),
        )
        activeGraph = if (getSharedPreferences("gradey-mode", 0).getBoolean("guest", false)) localGraph else graph
        updates.start()
    }
}
