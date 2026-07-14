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
        if (!forceRemote && cache.exists()) {
            runCatching { return@withContext CatalogParser.parse(cache.readText()) }
        }
        val remote = runCatching { downloadText(RAW_CATALOG_URL) }.getOrNull()
        if (!remote.isNullOrBlank()) {
            runCatching { cache.writeText(remote) }
            return@withContext CatalogParser.parse(remote)
        }
        if (cache.exists()) runCatching { return@withContext CatalogParser.parse(cache.readText()) }
        CatalogParser.parse(bundled)
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 18_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.inputStream.bufferedReader().use { return it.readText() }
    }
}
