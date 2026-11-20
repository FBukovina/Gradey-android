package com.bukovinafilip.gradey.data

import android.content.Context
import androidx.room.Room
import com.bukovinafilip.gradey.domain.DevicePushTokenClient
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.LinkedAccountRepository
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.network.BakalariNetworkClient
import com.bukovinafilip.gradey.network.SupabaseAccountClient
import com.bukovinafilip.gradey.network.GradeyJson
import com.bukovinafilip.gradey.network.SupabaseConfiguration
import com.bukovinafilip.gradey.network.SupabaseDevicePushTokenClient
import com.bukovinafilip.gradey.network.SupabaseGradeyAuthRepository

data class AndroidGradeyConfig(
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val googleWebClientId: String,
    val revenueCatAndroidKey: String,
    val useMockData: Boolean = true,
    val appleOAuthClientId: String = "",
)

class AndroidGradeyGraph private constructor(
    val schoolRepository: SchoolRepository,
    val gradeyAuthRepository: GradeyAuthRepository,
    val linkedAccountRepository: LinkedAccountRepository,
    val historyRepository: GradeyHistoryRepository,
    val devicePushTokenClient: DevicePushTokenClient,
    val stravaCZRepository: StravaCZRepository,
    val cache: RoomGradeyCache?,
    val isDemo: Boolean,
    val notificationSettings: NotificationSettingsRepository,
    val accountClient: SupabaseAccountClient? = null,
    val accountManagement: AccountManagementRepository? = null,
    val schoolDirectoryProvider: com.bukovinafilip.gradey.domain.SchoolDirectoryProvider,
    val schoolInsightStore: com.bukovinafilip.gradey.domain.SchoolInsightStore,
    val canteenAccountRepository: com.bukovinafilip.gradey.domain.CanteenAccountRepository,
    /** Friends whose timetables can be shown next to the user's own. Kept on this device only. */
    val friendRepository: com.bukovinafilip.gradey.domain.FriendRepository,
    /** The optional TŠ Vavruška dance course. Kept on this device only. */
    val vavruskaRepository: com.bukovinafilip.gradey.domain.VavruskaRepository,
) {
    companion object {
        fun create(context: Context, config: AndroidGradeyConfig): AndroidGradeyGraph {
            if (config.useMockData) {
                val auth = MockGradeyAuthRepository()
                val push = MockDevicePushTokenClient()
                return AndroidGradeyGraph(
                    schoolRepository = MockSchoolRepository(),
                    // Friends added in the demo use sample timetables and are gone when the demo ends.
                    friendRepository = com.bukovinafilip.gradey.domain.FriendRepository(
                        com.bukovinafilip.gradey.domain.InMemoryFriendStore(), com.bukovinafilip.gradey.network.DemoBakalariClient(),
                        com.bukovinafilip.gradey.network.DemoBakalariPublicTimetableClient(),
                    ),
                    // The demo's dance class reads sample pages; any password but "WRONG" connects it.
                    vavruskaRepository = com.bukovinafilip.gradey.domain.VavruskaRepository(
                        com.bukovinafilip.gradey.domain.DemoVavruskaClient(), com.bukovinafilip.gradey.domain.InMemoryVavruskaCourseStore(),
                        com.bukovinafilip.gradey.domain.InMemoryVavruskaCache(),
                    ),
                    canteenAccountRepository = MockCanteenAccountRepository(),
                    schoolInsightStore = com.bukovinafilip.gradey.domain.SchoolInsightStore(),
                    gradeyAuthRepository = auth,
                    linkedAccountRepository = MockLinkedAccountRepository(),
                    historyRepository = MockGradeyHistoryRepository(),
                    devicePushTokenClient = push,
                    stravaCZRepository = MockStravaCZRepository(),
                    cache = null,
                    isDemo = true,
                    schoolDirectoryProvider = BakalariSchoolDirectoryProvider(SecureJsonStore(context, "gradey-school-directory", GradeyJson)),
                    notificationSettings = NotificationSettingsRepository(SecureJsonStore(context, "gradey-demo-settings", GradeyJson), auth, push),
                )
            }

            val database = Room.databaseBuilder(context, GradeyDatabase::class.java, "gradey.db")
                .build()
            val cache = RoomGradeyCache(database.cacheEntries(), GradeyJson)
            val schoolSecureStore = SecureJsonStore(context, "gradey-school-secrets", GradeyJson)
            val authSecureStore = SecureJsonStore(context, "gradey-auth-secrets", GradeyJson)
            val sessionStore = SchoolSessionStore(schoolSecureStore)
            val supabase = SupabaseConfiguration(config.supabaseUrl, config.supabaseAnonKey, config.appleOAuthClientId)
            val appleSignInStore = PendingAppleSignInStore(context.applicationContext)

            val authRepository = SupabaseGradeyAuthRepository(
                configuration = supabase,
                sessionStore = {
                    if (it == null) {
                        authSecureStore.clear("gradey.auth.session")
                    } else {
                        authSecureStore.save("gradey.auth.session", it, com.bukovinafilip.gradey.model.GradeyAuthSession.serializer())
                    }
                },
                sessionLoader = { authSecureStore.load("gradey.auth.session", com.bukovinafilip.gradey.model.GradeyAuthSession.serializer()) },
                pendingAppleStore = appleSignInStore::save,
                pendingAppleLoader = appleSignInStore::load,
            )

            val pushClient = SupabaseDevicePushTokenClient(supabase)
            val accountClient = SupabaseAccountClient(supabase)
            val schoolInsightStore = AndroidSchoolInsightStore.create(context)
            return AndroidGradeyGraph(
                schoolRepository = ObservedSchoolRepository(AndroidSchoolRepository(
                    bakalariClient = BakalariNetworkClient(),
                    eduPageClient = com.bukovinafilip.gradey.network.OkHttpEduPageClient(),
                    sessionStore = sessionStore,
                    cache = cache,
                ), schoolInsightStore, cache),
                schoolInsightStore = schoolInsightStore,
                friendRepository = com.bukovinafilip.gradey.domain.FriendRepository(
                    AndroidFriendStore(SecureJsonStore(context, "gradey-friend-secrets", GradeyJson)), BakalariNetworkClient(),
                    com.bukovinafilip.gradey.network.BakalariPublicTimetableNetworkClient(),
                    JsonFriendTimetableCache(SecureJsonStore(context, "gradey-friend-timetables", GradeyJson)),
                ),
                vavruskaRepository = SecureJsonStore(context, "gradey-vavruska-secrets", GradeyJson).let { store ->
                    com.bukovinafilip.gradey.domain.VavruskaRepository(
                        com.bukovinafilip.gradey.network.VavruskaNetworkClient(), AndroidVavruskaCourseStore(store), JsonVavruskaCache(store),
                    )
                },
                gradeyAuthRepository = authRepository,
                canteenAccountRepository = SupabaseCanteenAccountRepository(com.bukovinafilip.gradey.network.SupabaseCanteenAccountClient(supabase), authRepository, authSecureStore),
                linkedAccountRepository = SupabaseLinkedAccountRepository(accountClient, authRepository, authSecureStore, sessionStore, cache),
                historyRepository = SupabaseHistoryRepository(accountClient, authRepository),
                devicePushTokenClient = pushClient,
                stravaCZRepository = AndroidStravaCZRepository(secureStore = SecureJsonStore(context, "gradey-strava-secrets", GradeyJson)),
                cache = cache,
                isDemo = false,
                schoolDirectoryProvider = BakalariSchoolDirectoryProvider(SecureJsonStore(context, "gradey-school-directory", GradeyJson)),
                notificationSettings = NotificationSettingsRepository(authSecureStore, authRepository, pushClient, accountClient),
                accountClient = accountClient,
                accountManagement = AccountManagementRepository(accountClient, authRepository),
            )
        }
    }
}
