package com.allvie.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.allvie.app.domain.model.LayoutMode
import com.allvie.app.domain.model.ThemeMode
import com.allvie.app.domain.model.UserPreferences
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    val preferencesFlow: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            UserPreferences(
                themeMode = ThemeMode.fromStoredValue(preferences[THEME_MODE]),
                layoutMode = LayoutMode.fromStoredValue(preferences[LAYOUT_MODE])
            )
        }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[THEME_MODE] = themeMode.name
        }
    }

    suspend fun setLayoutMode(layoutMode: LayoutMode) {
        dataStore.edit { preferences ->
            preferences[LAYOUT_MODE] = layoutMode.name
        }
    }

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val LAYOUT_MODE = stringPreferencesKey("layout_mode")
    }
}
