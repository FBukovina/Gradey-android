package com.bukovinafilip.gradey

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.bukovinafilip.gradey.model.AppLanguage
import java.util.Locale

internal object AppLanguages {
    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= 33) {
            val tag = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            return AppLanguage.entries.firstOrNull { it.languageTag.equals(tag, ignoreCase = true) } ?: AppLanguage.SYSTEM
        }
        return AppLanguage.fromStorage(context.getSharedPreferences("gradey-app-preferences", 0).getString("language", null))
    }

    fun select(activity: Activity, language: AppLanguage) {
        if (current(activity) == language) return
        activity.getSharedPreferences("gradey-app-preferences", 0).edit().putString("language", language.storageValue).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.languageTag.orEmpty())
        } else activity.recreate()
    }

    // ComponentActivity on older Android versions needs a localized base context.
    // Android 13+ owns the override and keeps the in-app and system pickers in sync.
    fun localizedContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = current(context).languageTag ?: return context
        return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }
}
