package dev.ligustah.lsnav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class FavoriteEditorState(
    val id: Long? = null,
    val draft: FavoriteDraft? = null,
    val saving: Boolean = false,
    val error: String? = null
)

data class NavigationState(
    val configuration: AppConfiguration = AppConfiguration(),
    val scooters: RemoteData<List<ScooterSummary>> = RemoteData(),
    val destination: RemoteData<NavigationTarget> = RemoteData(),
    val savedLocations: RemoteData<List<PlaceResult>> = RemoteData(),
    val personalPlaces: RemoteData<PersonalPlaces> = RemoteData(),
    val favoriteEditor: FavoriteEditorState = FavoriteEditorState(),
    val sending: Boolean = false,
    val lastCommandOutcome: CommandOutcome? = null,
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null
)

class NavigationViewModel @JvmOverloads constructor(
    application: Application,
    private val savedState: SavedStateHandle,
    private val gateway: NavigationGateway = NavigationRepository.get(application),
    private val resolver: CoordinateResolver = CoordinateResolver(application),
    private val configurations: kotlinx.coroutines.flow.Flow<AppConfiguration> = AppSettings(application).configuration
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(NavigationState(query = savedState["query"] ?: ""))
    val state: StateFlow<NavigationState> = mutableState
    private var storedConfiguration: AppConfiguration? = null
    private var scootersJob: Job? = null
    private var destinationJob: Job? = null
    private var locationsJob: Job? = null
    private var personalPlacesJob: Job? = null
    private var favoriteJob: Job? = null
    private var commandJob: Job? = null
    private var searchJob: Job? = null
    private var scooterGeneration = 0
    private var destinationGeneration = 0
    private var locationsGeneration = 0
    private var personalGeneration = 0
    private var searchGeneration = 0

    init {
        viewModelScope.launch {
            try {
                configurations.collect { config ->
                    if (config != storedConfiguration) {
                        val old = storedConfiguration
                        storedConfiguration = config
                        cancelResources()
                        commandJob?.cancel()
                        if (old?.accountKey != config.accountKey) favoriteJob?.cancel()
                        mutableState.value = mutableState.value.copy(
                            configuration = config, sending = false,
                            favoriteEditor = if (old?.accountKey == config.accountKey) mutableState.value.favoriteEditor else FavoriteEditorState(),
                            scooters = if (old?.accountKey == config.accountKey) mutableState.value.scooters else RemoteData(),
                            destination = RemoteData(), savedLocations = RemoteData(),
                            personalPlaces = if (old?.accountKey == config.accountKey) mutableState.value.personalPlaces else RemoteData(),
                            error = null
                        )
                        if (config.isAuthenticated) {
                            fetchScooters()
                            refreshPersonalPlaces()
                            refresh()
                        }
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Unable to load settings. Restart the app and retry.")
            }
        }
        viewModelScope.launch {
            gateway.personalPlacesChanges.collect { changedConfiguration ->
                val current = mutableState.value.configuration
                if (current.isAuthenticated && changedConfiguration.accountKey == current.accountKey) {
                    mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.copy(updatedAt = 0))
                    if (!mutableState.value.sending) refreshPersonalPlaces(forceRefresh = true)
                }
            }
        }
        viewModelScope.launch {
            gateway.changes.collect { change ->
                val current = mutableState.value.configuration
                if (current.isReady && change.configuration.accountKey == current.accountKey &&
                    change.configuration.scooterId == current.scooterId) {
                    mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.copy(updatedAt = 0))
                    if (!mutableState.value.sending) refreshDestination(current)
                }
            }
        }
    }

    private fun cancelResources() {
        scooterGeneration++; scootersJob?.cancel()
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        personalGeneration++; personalPlacesJob?.cancel()
    }

    fun fetchScooters(forceRefresh: Boolean = false) {
        val config = storedConfiguration ?: return
        if (!config.isAuthenticated) return
        val generation = ++scooterGeneration
        scootersJob?.cancel()
        mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.refreshing())
        scootersJob = viewModelScope.launch {
            try {
                val value = gateway.scooters(config, forceRefresh).distinctBy { it.id }
                if (generation == scooterGeneration && storedConfiguration?.accountKey == config.accountKey) {
                    val current = mutableState.value
                    val selectedId = current.configuration.scooterId
                    val selectedExists = selectedId == null || value.any { it.id == selectedId }
                    mutableState.value = current.copy(
                        scooters = RemoteData.loaded(value),
                        configuration = if (selectedExists) current.configuration else current.configuration.copy(scooterId = null, scooterName = ""),
                        destination = if (selectedExists) current.destination else RemoteData(),
                        savedLocations = if (selectedExists) current.savedLocations else RemoteData()
                    )
                    if (!selectedExists) {
                        destinationGeneration++; destinationJob?.cancel()
                        locationsGeneration++; locationsJob?.cancel()
                    }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == scooterGeneration) mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.failed(userMessage(error, config)))
            } finally {
                if (generation == scooterGeneration) mutableState.value = mutableState.value.copy(scooters = mutableState.value.scooters.copy(loading = false))
            }
        }
    }

    fun selectScooter(scooter: ScooterSummary) {
        val state = mutableState.value
        if (state.sending || scooter !in state.scooters.value.orEmpty()) return
        val config = state.configuration.copy(scooterId = scooter.id, scooterName = scooter.name)
        if (config == state.configuration) return
        mutableState.value = state.copy(configuration = config, destination = RemoteData(), savedLocations = RemoteData(), error = null)
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        refresh()
    }

    fun refreshIfStale() {
        val current = mutableState.value
        val config = current.configuration
        if (!config.isAuthenticated || current.sending) return
        if (current.scooters.stale(30_000)) fetchScooters()
        if (current.personalPlaces.stale(60_000)) refreshPersonalPlaces()
        if (config.isReady) {
            if (current.destination.stale(30_000)) refreshDestination(config)
            if (current.savedLocations.stale(60_000)) refreshSavedLocations(config)
        }
    }

    fun refresh() {
        val current = mutableState.value
        val config = current.configuration
        if (!config.isAuthenticated || current.sending) return
        fetchScooters(forceRefresh = true)
        refreshPersonalPlaces(forceRefresh = true)
        if (config.isReady) {
            refreshDestination(config)
            refreshSavedLocations(config)
        }
    }

    private fun refreshDestination(config: AppConfiguration) {
        val generation = ++destinationGeneration
        destinationJob?.cancel()
        mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.refreshing())
        destinationJob = viewModelScope.launch {
            try {
                val value = gateway.destination(config)
                if (generation == destinationGeneration && mutableState.value.configuration == config) mutableState.value = mutableState.value.copy(destination = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == destinationGeneration) mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.failed(userMessage(error, config)))
            } finally {
                if (generation == destinationGeneration) mutableState.value = mutableState.value.copy(destination = mutableState.value.destination.copy(loading = false))
            }
        }
    }

    private fun refreshSavedLocations(config: AppConfiguration) {
        val generation = ++locationsGeneration
        locationsJob?.cancel()
        mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.refreshing())
        locationsJob = viewModelScope.launch {
            try {
                val value = gateway.savedLocations(config)
                if (generation == locationsGeneration && mutableState.value.configuration == config) mutableState.value = mutableState.value.copy(savedLocations = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == locationsGeneration) mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.failed(userMessage(error, config)))
            } finally {
                if (generation == locationsGeneration) mutableState.value = mutableState.value.copy(savedLocations = mutableState.value.savedLocations.copy(loading = false))
            }
        }
    }

    fun refreshPersonalPlaces(forceRefresh: Boolean = false) {
        val config = storedConfiguration ?: return
        if (!config.isAuthenticated) return
        val generation = ++personalGeneration
        personalPlacesJob?.cancel()
        mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.refreshing())
        personalPlacesJob = viewModelScope.launch {
            try {
                val value = gateway.personalPlaces(config, forceRefresh)
                if (generation == personalGeneration && storedConfiguration?.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(personalPlaces = RemoteData.loaded(value))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == personalGeneration) mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.failed(userMessage(error, config)))
            } finally {
                if (generation == personalGeneration) mutableState.value = mutableState.value.copy(personalPlaces = mutableState.value.personalPlaces.copy(loading = false))
            }
        }
    }

    fun editFavorite(id: Long?, draft: FavoriteDraft?) {
        if (!mutableState.value.favoriteEditor.saving) mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(id, draft))
    }

    fun saveFavorite() {
        val current = mutableState.value
        val config = current.configuration
        val editor = current.favoriteEditor
        val draft = editor.draft ?: return
        if (editor.saving || !config.isAuthenticated || !draft.place.coordinates.isValid()) return
        if (config.favoritePermission == PermissionCapability.UpgradeRequired) {
            mutableState.value = current.copy(favoriteEditor = editor.copy(error = "Reconnect Sunshine and approve saved-place access to manage favorites."))
            return
        }
        favoriteJob?.cancel()
        mutableState.value = current.copy(favoriteEditor = editor.copy(saving = true, error = null))
        favoriteJob = viewModelScope.launch {
            try {
                if (editor.id == null) gateway.createFavorite(config, draft) else gateway.updateFavorite(config, editor.id, draft)
                if (storedConfiguration?.accountKey == config.accountKey) {
                    mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState())
                    refreshPersonalPlaces(forceRefresh = true)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (storedConfiguration?.accountKey == config.accountKey) mutableState.value = mutableState.value.copy(favoriteEditor = editor.copy(saving = false, error = userMessage(error, config)))
            }
        }
    }

    fun setFavorite(savedPlace: SavedPlace, favorite: Boolean) {
        val config = mutableState.value.configuration
        mutateFavorite(config) { gateway.setFavorite(config, savedPlace.id, savedPlace.place, favorite) }
    }

    fun deleteFavorite(savedPlace: SavedPlace) {
        val config = mutableState.value.configuration
        mutateFavorite(config) { gateway.deleteFavorite(config, savedPlace.id) }
    }

    private fun mutateFavorite(config: AppConfiguration, mutation: suspend () -> Unit) {
        if (!config.isAuthenticated || mutableState.value.favoriteEditor.saving) return
        if (config.favoritePermission == PermissionCapability.UpgradeRequired) {
            mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(error = "Reconnect Sunshine and approve saved-place access to manage favorites."))
            return
        }
        val owner = config.accountKey
        mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(saving = true))
        favoriteJob?.cancel()
        favoriteJob = viewModelScope.launch {
            try {
                mutation()
                if (storedConfiguration?.accountKey == owner) refreshPersonalPlaces(forceRefresh = true)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (storedConfiguration?.accountKey == owner) mutableState.value = mutableState.value.copy(favoriteEditor = FavoriteEditorState(error = userMessage(error, config)))
            } finally {
                if (storedConfiguration?.accountKey == owner) mutableState.value = mutableState.value.copy(favoriteEditor = mutableState.value.favoriteEditor.copy(saving = false))
            }
        }
    }

    fun updateQuery(value: String) {
        savedState["query"] = value
        searchGeneration++
        searchJob?.cancel()
        mutableState.value = mutableState.value.copy(query = value, results = emptyList(), searching = false)
        if (value.length < 3) return
        searchJob = viewModelScope.launch { kotlinx.coroutines.delay(500); search() }
    }

    fun resolveIncoming(text: String) {
        if (text.isBlank() || text.length > MapsIntentParser.MAX_INPUT_LENGTH) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutableState.value = mutableState.value.copy(query = text, results = emptyList(), searching = true, error = null)
        searchJob = viewModelScope.launch {
            try {
                val results = resolver.resolve(text)
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(results = results,
                    error = if (results.isEmpty()) "No destination found. Choose a place with a clear destination." else null)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(searching = false)
            }
        }
    }

    fun search() {
        val query = mutableState.value.query
        if (query.isBlank()) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(searching = true, error = null)
            try {
                val results = resolver.search(query)
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(results = results,
                    error = if (results.isEmpty()) "No destination found. Enter an address or exact coordinates." else null)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally {
                if (generation == searchGeneration) mutableState.value = mutableState.value.copy(searching = false)
            }
        }
    }

    fun sendToScooter(target: CommandTarget) {
        val current = mutableState.value
        val config = current.configuration
        val scooter = current.scooters.value.orEmpty().firstOrNull { it.id == target.configuration.scooterId }
        if (target.configuration != config || !config.isReady || scooter == null || scooter.online != true || current.sending) {
            mutableState.value = current.copy(error = if (scooter?.online == false) "This scooter is offline. Refresh or choose another scooter; your place is still here." else "Scooter or account access changed. Confirm the destination again.")
            return
        }
        if (target.place != null && !target.place.coordinates.isValid()) return
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        mutableState.value = current.copy(sending = true, error = null, lastCommandOutcome = null)
        commandJob = viewModelScope.launch {
            try {
                val outcome = gateway.sendToScooter(target)
                if (mutableState.value.configuration == target.configuration) {
                    mutableState.value = mutableState.value.copy(lastCommandOutcome = outcome)
                    refreshDestination(target.configuration)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                val uncertain = error is java.io.IOException || error is org.openapitools.client.infrastructure.ServerException
                mutableState.value = mutableState.value.copy(
                    error = if (uncertain) "The server response was interrupted. Check this scooter's destination before retrying." else userMessage(error, target.configuration),
                    lastCommandOutcome = if (uncertain) CommandOutcome.DeliveryUncertain(target) else null
                )
            } finally { mutableState.value = mutableState.value.copy(sending = false) }
        }
    }

    fun send(place: PlaceResult?, expectedConfiguration: AppConfiguration = mutableState.value.configuration) {
        val config = mutableState.value.configuration
        if (config != expectedConfiguration) {
            mutableState.value = mutableState.value.copy(error = "Scooter or settings changed. Confirm the destination again.")
            return
        }
        val availableScooter = config.scooterId != null && mutableState.value.scooters.value.orEmpty().any { it.id == config.scooterId }
        if (!config.isReady || !availableScooter || mutableState.value.sending) {
            if (config.isReady && !availableScooter) mutableState.value = mutableState.value.copy(error = "The selected scooter is no longer available. Choose a scooter and confirm again.")
            return
        }
        if (place != null && !place.coordinates.isValid()) return
        destinationGeneration++; destinationJob?.cancel()
        locationsGeneration++; locationsJob?.cancel()
        mutableState.value = mutableState.value.copy(sending = true, error = null)
        commandJob = viewModelScope.launch {
            try {
                if (place == null) gateway.clearDestination(config) else gateway.setDestination(config, place)
                if (mutableState.value.configuration == config) refreshDestination(config)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = userMessage(error))
            } finally { mutableState.value = mutableState.value.copy(sending = false) }
        }
    }
}
