package com.cloudstream.tr.hub

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cloudstream.tr.core.diagnostics.DiagnosticLogger
import com.cloudstream.tr.core.resolvers.DebridConfig
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CloudStreamHubPlugin : BasePlugin() {
    override fun load() {
        HubFederationLogcatBridge.install()
        registerMainAPI(CloudStreamHub())
    }

    fun openSettings(context: Context) {
        DebridConfig.initFromPreferences(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 30)
        }

        val rdLabel = TextView(context).apply {
            text = "Real-Debrid API Token (Opsiyonel):"
            textSize = 14f
        }
        val rdInput = EditText(context).apply {
            hint = "Real-Debrid API Key giriniz..."
            setText(DebridConfig.realDebridToken ?: "")
        }

        val torboxLabel = TextView(context).apply {
            text = "Torbox API Token (Opsiyonel):"
            textSize = 14f
            setPadding(0, 20, 0, 0)
        }
        val torboxInput = EditText(context).apply {
            hint = "Torbox API Key giriniz..."
            setText(DebridConfig.torboxToken ?: "")
        }

        val torrentCheck = CheckBox(context).apply {
            text = "P2P Torrent Kaynaklarını Göster (Torrentio/YTS ve diğer sağlayıcılar)"
            isChecked = DebridConfig.enableTorrentSources
            setPadding(0, 20, 0, 0)
        }

        val cdnCheck = CheckBox(context).apply {
            text = "4KHDHub Doğrudan CDN Akışlarını Önceliklendir"
            isChecked = DebridConfig.prioritizeDirectCdn
        }

        layout.addView(rdLabel)
        layout.addView(rdInput)
        layout.addView(torboxLabel)
        layout.addView(torboxInput)
        layout.addView(torrentCheck)
        layout.addView(cdnCheck)

        val scroll = ScrollView(context).apply {
            addView(layout)
        }

        AlertDialog.Builder(context)
            .setTitle("CloudStreamHub Ayarları")
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                DebridConfig.saveToPreferences(
                    context = context,
                    rdToken = rdInput.text.toString(),
                    torbox = torboxInput.text.toString(),
                    enableTorrents = torrentCheck.isChecked,
                    prioritizeCdn = cdnCheck.isChecked,
                    audioPref = DebridConfig.audioPreference
                )
                Toast.makeText(context, "CloudStreamHub ayarları kaydedildi!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }
}

private object HubFederationLogcatBridge {
    private var installed = false

    fun install() {
        synchronized(this) {
            if (installed) return

            DiagnosticLogger.addListener { event ->
                if (event.message.startsWith("Federation result:")) {
                    val message = event.message
                    val result = when {
                        "no confident match" in message -> "no-confident-match"
                        "provider.load returned no response" in message -> "load-no-response"
                        "no movie or requested episode link data" in message -> "no-link-data"
                        "confident " in message -> "confident-match"
                        else -> Regex(
                            "raw=(\\d+), providerEmitted=(\\d+).*valid=(\\d+), indeterminate=(\\d+), invalid=(\\d+), duplicates=(\\d+), dropped=(\\d+)"
                        ).find(message)?.let { match ->
                            val (raw, emitted, valid, indeterminate, invalid, duplicates, dropped) = match.destructured
                            "streams raw=$raw emitted=$emitted valid=$valid indeterminate=$indeterminate invalid=$invalid duplicates=$duplicates dropped=$dropped"
                        } ?: "federation-result"
                    }
                    Log.i("CloudStreamHub", "${event.provider} [${event.stage}] $result")
                }
            }
            installed = true
        }
    }
}
