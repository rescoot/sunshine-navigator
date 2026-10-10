package dev.ligustah.lsnav

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsState(
    val initialized: Boolean = false,
    val profile: AppProfile = AppProfile(),
    val draft: AppConfiguration = AppConfiguration(),
    val account: RemoteData<AccountIdentity?> = RemoteData(),
    val scooters: RemoteData<List<ScooterSummary>> = RemoteData(),
    val scooterReconnectRequired: Boolean = false,
    val authenticating: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val message: String? = null
) {
    val busy get() = authenticating || saving
}

class SettingsViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val gateway: NavigationGateway = NavigationRepository.get(application)
) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val mutableState = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = mutableState
    private var fetchJob: Job? = null
    private var accountJob: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            try {
                settings.profile.collect { profile ->
                    val previous = mutableState.value
                    val config = profile.configuration
                    val accountChanged = previous.profile.configuration.accountKey != config.accountKey
                    val draft = if (!previous.initialized || accountChanged || previous.draft == previous.profile.configuration) config else previous.draft
                    mutableState.value = previous.copy(initialized = true, profile = profile, draft = draft,
                        account = if (accountChanged) RemoteData() else previous.account,
                        scooters = if (accountChanged) RemoteData() else previous.scooters,
                        scooterReconnectRequired = if (accountChanged) false else previous.scooterReconnectRequired)
                    if (accountChanged) {
                        accountJob?.cancel()
                        if (config.isAuthenticated) { fetch(false); fetchAccount() }
                    }
                    if (!config.isAuthenticated) { generation++; fetchJob?.cancel(); accountJob?.cancel() }
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(initialized = true, error = "Couldn't load your settings. Close and reopen the app.") }
        }
    }

    private fun draft(value: AppConfiguration) {
        mutableState.value = mutableState.value.copy(draft = value, error = null, message = null)
    }
    fun discardChanges() = draft(mutableState.value.profile.configuration)
    fun updateToken(value: String) = draft(mutableState.value.draft.copy(token = value, oauthSessionId = null, grantedScopes = emptySet(), scooterId = null, scooterName = ""))
    fun updateUrl(value: String) = draft(mutableState.value.draft.copy(baseUrl = value, oauthSessionId = null, grantedScopes = emptySet(), scooterId = null, scooterName = ""))
    fun updateClientId(value: String) = draft(mutableState.value.draft.copy(oauthClientId = value))
    fun chooseOnDemand() = draft(mutableState.value.profile.configuration.copy(scooterId = null, scooterName = ""))
    fun select(scooter: ScooterSummary) {
        if (!mutableState.value.busy && scooter in mutableState.value.scooters.value.orEmpty()) {
            draft(mutableState.value.profile.configuration.copy(scooterId = scooter.id, scooterName = scooter.name))
        }
    }

    fun fetch(forceRefresh: Boolean = true) {
        val config = mutableState.value.profile.configuration
        if (!config.isAuthenticated || mutableState.value.saving) return
        val current = ++generation
        fetchJob?.cancel()
        mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.refreshing(), scooterReconnectRequired = false)
        fetchJob = viewModelScope.launch {
            try {
                val scooters = gateway.scooters(config, forceRefresh)
                if (current == generation) {
                    mutableState.value = mutableState.value.copy(scooters = RemoteData.loaded(scooters), scooterReconnectRequired = false)
                    if (config.scooterId != null && scooters.none { it.id == config.scooterId }) chooseOnDemand()
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (current == generation) mutableState.value = mutableState.value.copy(
                    scooters = mutableState.value.scooters.failed(scooterFetchMessage(error, config)),
                    scooterReconnectRequired = scooterFetchNeedsReconnect(error, config)
                )
            } finally {
                if (current == generation) mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.copy(loading = false))
            }
        }
    }

    fun fetchAccount() {
        val config = mutableState.value.profile.configuration
        if (!config.isAuthenticated) return
        accountJob?.cancel()
        mutableState.value = mutableState.value.copy(account = mutableState.value.account.refreshing())
        accountJob = viewModelScope.launch {
            try {
                val identity = gateway.account(config)
                if (mutableState.value.profile.configuration.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(account = RemoteData.loaded(identity))
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                if (mutableState.value.profile.configuration.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(account = RemoteData(error = "Couldn't load your account"))
            }
        }
    }

    fun beginLogin(): Boolean {
        if (mutableState.value.busy) return false
        mutableState.value = mutableState.value.copy(authenticating = true, error = null)
        return true
    }
    fun authError(error: Exception) {
        mutableState.value = mutableState.value.copy(error = userMessage(error), authenticating = false)
    }
    fun completeLogin(intent: Intent?) {
        viewModelScope.launch {
            try {
                val config = OAuthManager.get(getApplication()).complete(intent)
                settings.saveProfile(AppProfile(config, mutableState.value.profile.onboardingComplete))
                draft(config)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { authError(error)
            } finally { mutableState.value = mutableState.value.copy(authenticating = false) }
        }
    }
    fun connectManual() {
        if (!beginLogin()) return
        val draft = mutableState.value.draft
        viewModelScope.launch {
            try {
                require(draft.token.isNotBlank()) { "Enter your API token." }
                val config = draft.copy(token = draft.token.trim(), baseUrl = ApiClientProvider.normalizeBaseUrl(draft.baseUrl), oauthSessionId = null, grantedScopes = emptySet(), scooterId = null, scooterName = "")
                val scooters = gateway.scooters(config, true)
                settings.saveProfile(AppProfile(config, mutableState.value.profile.onboardingComplete))
                draft(config)
                mutableState.value = mutableState.value.copy(scooters = RemoteData.loaded(scooters), scooterReconnectRequired = false)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { authError(error)
            } finally { mutableState.value = mutableState.value.copy(authenticating = false) }
        }
    }
    fun save() {
        val state = mutableState.value
        if (state.busy || !state.profile.configuration.isAuthenticated) return
        mutableState.value = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                require(state.draft.scooterId == null || state.scooters.value.orEmpty().any { it.id == state.draft.scooterId }) { "Choose an available scooter, or choose one each time." }
                val config = state.profile.configuration.copy(scooterId = state.draft.scooterId, scooterName = state.draft.scooterName)
                settings.saveProfile(AppProfile(config, true))
                draft(config)
                mutableState.value = mutableState.value.copy(message = "Preference saved")
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally { mutableState.value = mutableState.value.copy(saving = false) }
        }
    }
    fun signOut() {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(saving = true)
        viewModelScope.launch {
            try {
                val config = mutableState.value.profile.configuration
                val revoked = OAuthManager.get(getApplication()).revokeAndClear(config)
                settings.saveProfile(AppProfile())
                (gateway as? NavigationRepository)?.clearCaches()
                draft(AppConfiguration())
                if (!revoked) mutableState.value = mutableState.value.copy(error = "Signed out locally. Sunshine could not be reached to revoke access; remove this app in Sunshine's authorized applications.")
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally { mutableState.value = mutableState.value.copy(saving = false) }
        }
    }
}
