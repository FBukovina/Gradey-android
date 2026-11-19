# Gradey release rules. Libraries (kotlinx.serialization, OkHttp, Retrofit, Room, WorkManager,
# Firebase, RevenueCat, Intercom) ship their own consumer rules; only gaps are listed here.

# Readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Credential Manager finds the Play services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }

# Compile-time-only annotations referenced by Tink (security-crypto) and Jsoup.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
