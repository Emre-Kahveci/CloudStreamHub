package com.cloudstream.tr.hub

import android.app.AlertDialog
import android.content.Context
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cloudstream.tr.core.resolvers.DebridConfig
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CloudStreamHubPlugin : BasePlugin() {
    override fun load() {
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
            text = "P2P Torrent Kaynaklarını Göster (Torrentio/YTS)"
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
