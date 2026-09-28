package com.caeamer.beikeschedule.update

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.updateDataStore by preferencesDataStore(
    name = "app_update",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class UpdateStore(private val context: Context) {
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): UpdatePreferences = decode(context.updateDataStore.data.first()[key])

    suspend fun edit(transform: (UpdatePreferences) -> UpdatePreferences) {
        context.updateDataStore.edit { preferences ->
            preferences[key] = json.encodeToString(transform(decode(preferences[key])))
        }
    }

    private fun decode(value: String?): UpdatePreferences = value?.let {
        runCatching { json.decodeFromString<UpdatePreferences>(it) }.getOrNull()
    } ?: UpdatePreferences()
}
