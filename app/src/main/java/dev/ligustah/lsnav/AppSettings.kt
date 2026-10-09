package dev.ligustah.lsnav

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

data class AppConfiguration(
    val token: String = "",
    val baseUrl: String = AppSettings.DEFAULT_BASE_URL,
    val scooterId: Long? = null,
    val scooterName: String = "",
    val oauthSessionId: String? = null,
    val oauthClientId: String = OAuthManager.DEFAULT_CLIENT_ID,
    val grantedScopes: Set<String> = emptySet()
) {
    val isAuthenticated: Boolean get() = token.isNotBlank() || oauthSessionId != null
    val isReady: Boolean get() = isAuthenticated && scooterId != null
    val accountKey: AppConfiguration get() = copy(scooterId = null, scooterName = "", grantedScopes = emptySet())
    val canRead: Boolean get() = token.isNotBlank() || "read" in grantedScopes
    val canControlScooter: Boolean get() = token.isNotBlank() || "scooter_control" in grantedScopes
    val favoritePermission: PermissionCapability get() = when {
        oauthSessionId == null -> PermissionCapability.Unknown
        "write" in grantedScopes -> PermissionCapability.Granted
        else -> PermissionCapability.UpgradeRequired
    }
    val canManageFavorites: Boolean get() = favoritePermission == PermissionCapability.Granted
    val missingOAuthScopes: Set<String> get() = if (oauthSessionId == null) emptySet() else REQUIRED_OAUTH_SCOPES - grantedScopes
    val needsPermissionUpgrade: Boolean get() = missingOAuthScopes.isNotEmpty()
    companion object { val REQUIRED_OAUTH_SCOPES = setOf("read", "write", "scooter_control") }
}

enum class PermissionCapability { Granted, UpgradeRequired, Unknown }

class AppSettings(context: Context) {
    private val context = context.applicationContext
    companion object {
        val TOKEN_KEY = stringPreferencesKey("api_token")
        val BASE_URL_KEY = stringPreferencesKey("base_url")
        val SCOOTER_ID_KEY = longPreferencesKey("scooter_id")
        val SCOOTER_NAME_KEY = stringPreferencesKey("scooter_name")
        val OAUTH_SESSION_KEY = stringPreferencesKey("oauth_session")
        val OAUTH_CLIENT_KEY = stringPreferencesKey("oauth_client")
        val OAUTH_SCOPES_KEY = stringPreferencesKey("oauth_scopes")
        val ONBOARDING_KEY = booleanPreferencesKey("onboarding_complete")
        
        const val DEFAULT_BASE_URL = "https://sunshine.rescoot.org"
    }

    val profile: Flow<AppProfile> = context.dataStore.data.map {
        val configuration = AppConfiguration(it[TOKEN_KEY] ?: "", it[BASE_URL_KEY] ?: DEFAULT_BASE_URL, it[SCOOTER_ID_KEY], it[SCOOTER_NAME_KEY] ?: "",
            it[OAUTH_SESSION_KEY], it[OAUTH_CLIENT_KEY] ?: OAuthManager.DEFAULT_CLIENT_ID,
            (it[OAUTH_SCOPES_KEY] ?: "").split(' ').filter(String::isNotBlank).toSet())
        AppProfile(configuration, it[ONBOARDING_KEY] ?: configuration.isAuthenticated)
    }
    val configuration: Flow<AppConfiguration> = profile.map { it.configuration }

    val token: Flow<String?> = context.dataStore.data.map { it[TOKEN_KEY] }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL_KEY] ?: DEFAULT_BASE_URL }
    val scooterId: Flow<Long?> = context.dataStore.data.map { it[SCOOTER_ID_KEY] }
    val scooterName: Flow<String?> = context.dataStore.data.map { it[SCOOTER_NAME_KEY] }

    suspend fun saveSettings(token: String, baseUrl: String, scooterId: Long?, scooterName: String) =
        saveConfiguration(AppConfiguration(token, baseUrl, scooterId, scooterName))

    suspend fun saveConfiguration(configuration: AppConfiguration) = save(configuration, null)
    suspend fun saveProfile(profile: AppProfile) = save(profile.configuration, profile.onboardingComplete)

    private suspend fun save(configuration: AppConfiguration, onboardingComplete: Boolean?) {
        val (token, baseUrl, scooterId, scooterName) = configuration
        context.dataStore.edit {
            if (!configuration.isAuthenticated) it[ONBOARDING_KEY] = false
            else if (onboardingComplete != null) it[ONBOARDING_KEY] = onboardingComplete
            if (configuration.oauthSessionId == null) it.remove(OAUTH_SESSION_KEY) else it[OAUTH_SESSION_KEY] = configuration.oauthSessionId
            if (configuration.grantedScopes.isEmpty()) it.remove(OAUTH_SCOPES_KEY) else it[OAUTH_SCOPES_KEY] = configuration.grantedScopes.sorted().joinToString(" ")
            it[OAUTH_CLIENT_KEY] = configuration.oauthClientId
            it[TOKEN_KEY] = token
            it[BASE_URL_KEY] = baseUrl
            if (scooterId == null) {
                it.remove(SCOOTER_ID_KEY)
                it.remove(SCOOTER_NAME_KEY)
            } else {
                it[SCOOTER_ID_KEY] = scooterId
                it[SCOOTER_NAME_KEY] = scooterName
            }
        }
    }
}
