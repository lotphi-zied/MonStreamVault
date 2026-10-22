package com.streamvault.app

import android.content.Context
import android.util.Log
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistBootstrap @Inject constructor(
    @ApplicationContext context: Context,
    private val providerRepository: ProviderRepository,
    private val validateAndAddProvider: ValidateAndAddProvider
) {
    private companion object {
        const val TAG = "PlaylistBootstrap"
        const val CONFIG_URL = "http://192.168.1.111/playlist.txt"
        const val PROVIDER_NAME = "Maison"
        const val KEY_LINE = "last_line"
        const val KEY_ID = "provider_id"
    }

    private val prefs = context.getSharedPreferences("playlist_bootstrap", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    suspend fun run() = mutex.withLock {
        val line = fetchLine() ?: return@withLock

        val storedId = prefs.getLong(KEY_ID, -1L).takeIf { it >= 0 }
        val existing = storedId?.let { providerRepository.getProvider(it) }
        if (existing != null && prefs.getString(KEY_LINE, null) == line) return@withLock

        val result = validateAndAddProvider.addM3u(
            M3uProviderSetupCommand(
                url = line,
                name = PROVIDER_NAME,
                existingProviderId = existing?.id
            )
        )
        val provider = when (result) {
            is ValidateAndAddProviderResult.Success -> result.provider
            is ValidateAndAddProviderResult.SavedWithWarning -> result.provider
            else -> {
                Log.w(TAG, "Ajout du fournisseur impossible: $result")
                return@withLock
            }
        }
        prefs.edit().putLong(KEY_ID, provider.id).putString(KEY_LINE, line).apply()
        providerRepository.setActiveProvider(provider.id)
    }

    private suspend fun fetchLine(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                useCaches = false
            }
            try {
                if (conn.responseCode != 200) return@withContext null
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.map { it.trim().trimStart('\uFEFF') }
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "playlist.txt injoignable: ${e.message}")
            null
        }
    }
}