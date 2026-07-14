package com.apexlions.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object CatalogRepository {
    const val RAW_CATALOG_URL = "https://raw.githubusercontent.com/apexlions16/music-project/main/catalog/catalog.json"
    private const val CACHE_FILE = "catalog-cache.json"

    suspend fun load(context: Context, forceRemote: Boolean = false): Catalog = withContext(Dispatchers.IO) {
        val cache = context.getFileStreamPath(CACHE_FILE)
        val bundled = context.assets.open("catalog.json").bufferedReader().use { it.readText() }

        // Uygulama her açılışta uzaktaki kataloğu kontrol eder. GitHub ulaşılamazsa güvenli biçimde önbelleğe düşer.
        val remote = runCatching { downloadText("$RAW_CATALOG_URL?aurora=${System.currentTimeMillis()}") }.getOrNull()
        if (!remote.isNullOrBlank()) {
            runCatching { cache.writeText(remote) }
            return@withContext CatalogParser.parse(remote)
        }
        if (cache.exists()) {
            runCatching { return@withContext CatalogParser.parse(cache.readText()) }
        }
        CatalogParser.parse(bundled)
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("User-Agent", "AuroraMusic/0.3.0")
        connection.inputStream.bufferedReader().use { return it.readText() }
    }
}
