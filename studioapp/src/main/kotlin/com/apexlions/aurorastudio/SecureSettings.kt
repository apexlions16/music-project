package com.apexlions.aurorastudio

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

internal data class StudioConfig(
    val githubRepo: String = "apexlions16/music-project",
    val githubBranch: String = "main",
    val catalogPath: String = "catalog/catalog.json",
    val githubToken: String = "",
    val hfRepo: String = "hcywashere/m-project",
    val hfToken: String = "",
)

internal object SecureSettings {
    private const val SECURE_FILE = "aurora_studio_mobile_secure"
    private const val FALLBACK_FILE = "aurora_studio_mobile_fallback"
    private const val COMMIT_LEDGER = "hf_commit_ledger"

    private fun prefs(context: Context): SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    fun load(context: Context): StudioConfig {
        val p = prefs(context)
        return StudioConfig(
            githubRepo = p.getString("github_repo", "apexlions16/music-project").orEmpty(),
            githubBranch = p.getString("github_branch", "main").orEmpty(),
            catalogPath = p.getString("catalog_path", "catalog/catalog.json").orEmpty(),
            githubToken = p.getString("github_token", "").orEmpty(),
            hfRepo = p.getString("hf_repo", "hcywashere/m-project").orEmpty(),
            hfToken = p.getString("hf_token", "").orEmpty(),
        )
    }

    fun save(context: Context, value: StudioConfig) {
        prefs(context).edit()
            .putString("github_repo", value.githubRepo.trim())
            .putString("github_branch", value.githubBranch.trim())
            .putString("catalog_path", value.catalogPath.trim())
            .putString("github_token", value.githubToken.trim())
            .putString("hf_repo", value.hfRepo.trim())
            .putString("hf_token", value.hfToken.trim())
            .apply()
    }

    fun recentCommits(context: Context): MutableList<Long> {
        val cutoff = System.currentTimeMillis() - 3_600_000L
        val raw = prefs(context).getString(COMMIT_LEDGER, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { array.optLong(it) }
                .filter { it >= cutoff }
                .sorted()
                .toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun recordCommit(context: Context) {
        val values = recentCommits(context)
        values += System.currentTimeMillis()
        val array = JSONArray()
        values.takeLast(128).forEach(array::put)
        prefs(context).edit().putString(COMMIT_LEDGER, array.toString()).apply()
    }
}
