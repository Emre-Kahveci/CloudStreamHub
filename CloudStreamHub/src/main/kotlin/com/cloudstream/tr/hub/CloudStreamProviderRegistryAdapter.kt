package com.cloudstream.tr.hub

import com.cloudstream.tr.core.diagnostics.DiagnosticCategory
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.diagnostics.DiagnosticStage
import com.lagradost.cloudstream3.MainAPI

object CloudStreamProviderRegistryAdapter {

    /**
     * Dynamically queries CloudStream's internal APIHolder for registered Turkish providers.
     * Operates purely via reflection on the host application without hardcoding provider classes.
     */
    fun getRegisteredTurkishProviders(excludeName: String = "CloudStreamHub"): List<MainAPI> {
        val discovered = mutableListOf<MainAPI>()

        try {
            val holderClass = Class.forName("com.lagradost.cloudstream3.APIHolder")
            val holderInstance = try {
                holderClass.getField("INSTANCE").get(null)
            } catch (_: Exception) {
                null
            }

            val getterNames = listOf("getAllProviders", "getApis", "getPlugins")
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
                val candidateFields = listOf("allProviders", "apis", "loadedPlugins")
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

        val filtered = discovered.distinctBy { it.name }.filter { it.lang == "tr" && it.name != excludeName }
        if (filtered.isEmpty()) {
            DiagnosticLogger.log(
                provider = "CloudStreamHub",
                stage = DiagnosticStage.LOAD,
                category = DiagnosticCategory.SOURCE_DISCOVERY,
                message = "No external Turkish providers registered in CloudStream. Ensure individual provider plugins are installed."
            )
        }
        return filtered
    }
}
