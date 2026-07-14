package com.apexlions.music

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppSettings {
    private const val FILE_NAME = "aurora_secure_settings"
    private const val FALLBACK_FILE_NAME = "aurora_settings_fallback"
    private const val HF_TOKEN = "hf_read_token"
    private const val ANIMATED_COVERS = "animated_covers"

    private fun preferences(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun huggingFaceToken(context: Context): String = preferences(context).getString(HF_TOKEN, "").orEmpty()
    fun setHuggingFaceToken(context: Context, value: String) {
        preferences(context).edit().putString(HF_TOKEN, value.trim()).apply()
    }

    fun animatedCovers(context: Context): Boolean = preferences(context).getBoolean(ANIMATED_COVERS, true)
    fun setAnimatedCovers(context: Context, value: Boolean) {
        preferences(context).edit().putBoolean(ANIMATED_COVERS, value).apply()
    }
}
