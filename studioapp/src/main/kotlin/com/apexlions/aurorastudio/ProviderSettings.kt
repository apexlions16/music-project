package com.apexlions.aurorastudio

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal object ProviderSettings {
    private const val FILE = "aurora_studio_provider_secure_v2"
    private const val FALLBACK = "aurora_studio_provider_fallback_v2"

    private fun prefs(context: Context): SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(FALLBACK, Context.MODE_PRIVATE)
    }

    fun load(context: Context): ProviderConfig {
        val p = prefs(context)
        return ProviderConfig(
            spotifyClientId = p.getString("spotify_client_id", "").orEmpty(),
            spotifyClientSecret = p.getString("spotify_client_secret", "").orEmpty(),
            musicBrainzContact = p.getString("musicbrainz_contact", "").orEmpty(),
        )
    }

    fun save(context: Context, value: ProviderConfig) {
        prefs(context).edit()
            .putString("spotify_client_id", value.spotifyClientId.trim())
            .putString("spotify_client_secret", value.spotifyClientSecret.trim())
            .putString("musicbrainz_contact", value.musicBrainzContact.trim())
            .apply()
    }
}
