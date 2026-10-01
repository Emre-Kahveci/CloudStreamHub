package com.cloudstream.tr.hub

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.lagradost.cloudstream3.MainAPI

object CloudStreamProviderRegistryAdapter {

    private val knownProviderClasses = listOf(
        "com.cloudstream.tr.hdfilmcehennemi.HDFilmCehennemi",
        "com.cloudstream.tr.filmmakinesi.FilmMakinesi",
        "com.cloudstream.tr.kultfilmler.KultFilmler",
        "com.cloudstream.tr.sinemacx.SinemaCX",
        "com.cloudstream.tr.hdfilmdelisi.HDFilmDelisi",
        "com.cloudstream.tr.yesilcamtv.YesilCamTv",
        "com.cloudstream.tr.sezonlukdizi.SezonlukDizi",
        "com.cloudstream.tr.diziyou.DiziYou",
        "com.cloudstream.tr.dizikorea.DiziKorea",
        "com.cloudstream.tr.dizilla.Dizilla",
        "com.cloudstream.tr.jetfilmizle.JetFilmIzle"
    )

    /**
     * Combines providers registered in CloudStream's APIHolder with known bundled providers.
     */
    fun getRegisteredProviders(excludeName: String = "CloudStreamHub"): List<MainAPI> {
        val discovered = mutableListOf<MainAPI>()

        // 1. Try reflection on CloudStream APIHolder
        try {
            val holderClass = Class.forName("com.lagradost.cloudstream3.APIHolder")
            val holderInstance = try {
                holderClass.getField("INSTANCE").get(null)
            } catch (_: Exception) {
                null
            }

            val getterNames = listOf("getAllProviders", "getApis", "getPlugins", "getAllApis")
            for (mName in getterNames) {
                try {
                    val m = holderClass.getMethod(mName)
                    m.isAccessible = true
                    val obj = m.invoke(holderInstance)
                    val items = when (obj) {
                        is Array<*> -> obj.filterIsInstance<MainAPI>()
                        is Collection<*> -> obj.filterIsInstance<MainAPI>()
                        else -> emptyList()
                    }
                    if (items.isNotEmpty()) {
                        discovered.addAll(items)
                        break
                    }
                } catch (_: Exception) {}
            }

            if (discovered.isEmpty()) {
                val candidateFields = listOf("allProviders", "apis", "loadedPlugins", "plugins")
                for (fieldName in candidateFields) {
                    try {
                        val field = holderClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val obj = field.get(holderInstance)
                        val items = when (obj) {
                            is Array<*> -> obj.filterIsInstance<MainAPI>()
                            is Collection<*> -> obj.filterIsInstance<MainAPI>()
                            else -> emptyList()
                        }
                        if (items.isNotEmpty()) {
                            discovered.addAll(items)
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log(
                provider = "CloudStreamHub",
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "APIHolder reflection failed: ${e.message}",
                throwable = e
            )
        }

        // 2. Merge bundled providers even when APIHolder already has separately installed APIs.
        // A non-empty APIHolder list must not hide providers packaged with CloudStreamHub.
        for (className in knownProviderClasses) {
            try {
                val clazz = Class.forName(className)
                val instance = clazz.getDeclaredConstructor().newInstance() as? MainAPI
                if (instance != null && discovered.none { it.name == instance.name }) {
                    discovered.add(instance)
                }
            } catch (_: Throwable) {}
        }

        val filtered = discovered.distinctBy { it.name }.filter { it.name != excludeName }
        if (filtered.isEmpty()) {
            DiagnosticLogger.log(
                provider = "CloudStreamHub",
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "No external providers found in CloudStream's registry or bundled modules."
            )
        }
        return filtered
    }
}
