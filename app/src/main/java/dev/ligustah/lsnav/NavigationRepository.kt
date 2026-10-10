package dev.ligustah.lsnav

import dev.ligustah.lsnav.api.generated.models.DestinationInput
import dev.ligustah.lsnav.api.generated.models.SavedDestinationInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.openapitools.client.infrastructure.ClientException
import org.openapitools.client.infrastructure.ServerException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface NavigationGateway {
    val changes: Flow<DestinationChange> get() = emptyFlow()
    val personalPlacesChanges: Flow<AppConfiguration> get() = emptyFlow()
    suspend fun scooters(configuration: AppConfiguration, forceRefresh: Boolean = false): List<ScooterSummary>
    suspend fun personalPlaces(configuration: AppConfiguration, forceRefresh: Boolean = false): PersonalPlaces = PersonalPlaces()
    suspend fun createFavorite(configuration: AppConfiguration, draft: FavoriteDraft): SavedPlace = throw UnsupportedOperationException("Favorites are unavailable")
    suspend fun updateFavorite(configuration: AppConfiguration, id: Long, draft: FavoriteDraft): SavedPlace = throw UnsupportedOperationException("Favorites are unavailable")
    suspend fun setFavorite(configuration: AppConfiguration, id: Long, place: PlaceResult, favorite: Boolean): SavedPlace = throw UnsupportedOperationException("Favorites are unavailable")
    suspend fun deleteFavorite(configuration: AppConfiguration, id: Long) { throw UnsupportedOperationException("Favorites are unavailable") }
    suspend fun sendToScooter(target: CommandTarget): CommandOutcome {
        if (!target.configuration.isReady) throw IllegalArgumentException("Choose a scooter")
        if (target.place == null) clearDestination(target.configuration) else setDestination(target.configuration, target.place)
        return CommandOutcome.ServerAccepted(target)
    }
    suspend fun savedLocations(configuration: AppConfiguration): List<PlaceResult> = emptyList()
    suspend fun destination(configuration: AppConfiguration): NavigationTarget
    suspend fun setDestination(configuration: AppConfiguration, place: PlaceResult)
    suspend fun clearDestination(configuration: AppConfiguration)
}

class NavigationRepository(
    private val context: android.content.Context? = null,
    private val providerFactory: (AppConfiguration) -> ApiClientProvider = { ApiClientProvider(it.baseUrl, it.token) }
) : NavigationGateway {
    private data class Cache<T>(val account: AppConfiguration, val value: T, val time: Long = System.currentTimeMillis())
    private val scootersMutex = Mutex()
    private val placesMutex = Mutex()
    private val commandMutex = Mutex()
    private var scootersCache: Cache<List<ScooterSummary>>? = null
    private var placesCache: Cache<PersonalPlaces>? = null
    override val changes = MutableSharedFlow<DestinationChange>(extraBufferCapacity = 16)
    override val personalPlacesChanges = MutableSharedFlow<AppConfiguration>(extraBufferCapacity = 16)

    override suspend fun scooters(configuration: AppConfiguration, forceRefresh: Boolean): List<ScooterSummary> = scootersMutex.withLock {
        scootersCache?.takeIf { !forceRefresh && it.account == configuration.accountKey && System.currentTimeMillis() - it.time < 30_000 }?.let { return@withLock it.value }
        val result = request(configuration) { provider -> provider.getScootersApi().listScooters().mapNotNull { scooter ->
            scooter.id?.let { id -> ScooterSummary(
                id = id, name = scooter.name.ifBlank { "Scooter $id" }, online = scooter.online, state = scooter.state,
                vin = scooter.vin, color = scooter.color, colorId = scooter.colorId, colorHex = scooter.colorHex,
                model = scooter.model, licensePlate = scooter.licensePlate, lastSeenAt = scooter.lastSeenAt?.toString(),
                images = scooter.images?.let { ScooterImages(it.front, it.left, it.right) } ?: ScooterImages()
            ) }
        }.distinctBy { it.id } }
        scootersCache = Cache(configuration.accountKey, result)
        result
    }
    override suspend fun personalPlaces(configuration: AppConfiguration, forceRefresh: Boolean): PersonalPlaces = placesMutex.withLock {
        placesCache?.takeIf { !forceRefresh && it.account == configuration.accountKey && System.currentTimeMillis() - it.time < 60_000 }?.let { return@withLock it.value }
        val result = request(configuration) { provider ->
            val response = provider.getNavigationApi().getPersonalPlaces()
            fun places(values: List<dev.ligustah.lsnav.api.generated.models.UserSavedPlace>) = values.mapNotNull {
                val coordinates = Coordinates(it.latitude, it.longitude)
                if (coordinates.isValid()) SavedPlace(it.id, PlaceResult(coordinates, it.label.ifBlank { coordinates.display() }), it.isFavorite ?: false, it.searchKey) else null
            }
            PersonalPlaces(places(response.favorites), places(response.recent))
        }
        placesCache = Cache(configuration.accountKey, result)
        result
    }
    override suspend fun createFavorite(configuration: AppConfiguration, draft: FavoriteDraft): SavedPlace {
        val result = request(configuration) { it.getNavigationApi().createSavedDestination(draft.toInput()) }.toDomain()
        invalidatePlaces(configuration)
        return result
    }
    override suspend fun updateFavorite(configuration: AppConfiguration, id: Long, draft: FavoriteDraft): SavedPlace {
        val result = request(configuration) { it.getNavigationApi().updateSavedDestination(id, draft.toInput()) }.toDomain()
        invalidatePlaces(configuration)
        return result
    }
    override suspend fun setFavorite(configuration: AppConfiguration, id: Long, place: PlaceResult, favorite: Boolean): SavedPlace {
        val result = request(configuration) { it.getNavigationApi().updateSavedDestination(id, FavoriteDraft(place.label, place).toInput(favorite)) }.toDomain()
        invalidatePlaces(configuration)
        return result
    }
    override suspend fun deleteFavorite(configuration: AppConfiguration, id: Long) {
        request(configuration) { it.getNavigationApi().deleteSavedDestination(id) }
        invalidatePlaces(configuration)
    }
    override suspend fun sendToScooter(target: CommandTarget): CommandOutcome {
        require(target.configuration.isReady) { "Choose an available scooter" }
        target.place?.let { require(it.coordinates.isValid()) { "Invalid destination coordinates" } }
        if (target.place == null) clearDestination(target.configuration) else setDestination(target.configuration, target.place)
        return CommandOutcome.ServerAccepted(target)
    }

    override suspend fun savedLocations(configuration: AppConfiguration) = request(configuration) {
        it.getNavigationApi().getSavedLocations(requireNotNull(configuration.scooterId)).locations.mapNotNull { location ->
            val coordinates = Coordinates(location.latitude, location.longitude)
            if (coordinates.isValid()) PlaceResult(coordinates, location.label) else null
        }
    }
    override suspend fun destination(configuration: AppConfiguration) = request(configuration) {
        val response = it.getNavigationApi().getDestination(requireNotNull(configuration.scooterId))
        val coordinates = if (response.latitude != null && response.longitude != null) Coordinates(response.latitude, response.longitude).takeIf { value -> value.isValid() } else null
        NavigationTarget(coordinates?.let { value -> PlaceResult(value, response.address?.takeIf { label -> label.isNotBlank() } ?: value.display()) })
    }
    override suspend fun setDestination(configuration: AppConfiguration, place: PlaceResult) = commandMutex.withLock {
        require(place.coordinates.isValid()) { "Invalid destination coordinates" }
        request(configuration) {
            it.getNavigationApi().setDestination(requireNotNull(configuration.scooterId),
                DestinationInput(place.coordinates.latitude, place.coordinates.longitude, place.label))
        }
        changes.tryEmit(DestinationChange(configuration))
        Unit
    }
    override suspend fun clearDestination(configuration: AppConfiguration) = commandMutex.withLock {
        request(configuration) { it.getNavigationApi().clearDestination(requireNotNull(configuration.scooterId)) }
        changes.tryEmit(DestinationChange(configuration))
        Unit
    }

    suspend fun clearCaches() {
        scootersMutex.withLock { scootersCache = null }
        placesMutex.withLock { placesCache = null }
    }

    private suspend fun invalidatePlaces(configuration: AppConfiguration) {
        placesMutex.withLock { placesCache = null }
        personalPlacesChanges.tryEmit(configuration)
    }
    private fun FavoriteDraft.toInput(favorite: Boolean = true) = SavedDestinationInput(
        label = label.trim(), latitude = place.coordinates.latitude, longitude = place.coordinates.longitude,
        searchKey = searchKey, isFavorite = favorite
    )
    private fun dev.ligustah.lsnav.api.generated.models.UserSavedPlace.toDomain(): SavedPlace {
        val coordinates = Coordinates(latitude, longitude)
        require(coordinates.isValid()) { "Server returned invalid saved-place coordinates" }
        return SavedPlace(id, PlaceResult(coordinates, label), isFavorite ?: true, searchKey)
    }

    private suspend fun <T> request(configuration: AppConfiguration, block: (ApiClientProvider) -> T): T {
        if (configuration.oauthSessionId == null) return execute(configuration, block)
        val manager = OAuthManager.get(context ?: throw OAuthLoginRequired())
        val token = manager.accessToken(configuration)
        return try {
            execute(configuration.copy(token = token), block)
        } catch (error: ClientException) {
            if (error.statusCode != 401) throw error
            execute(configuration.copy(token = manager.accessToken(configuration, forceRefresh = true)), block)
        }
    }

    private suspend fun <T> execute(configuration: AppConfiguration, block: (ApiClientProvider) -> T): T {
        val provider = providerFactory(configuration)
        return suspendCancellableCoroutine { continuation ->
            val task = FutureTask {
                if (continuation.isActive) {
                    try {
                        val result = block(provider)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
            continuation.invokeOnCancellation {
                provider.cancel()
                task.cancel(true)
            }
            executor.execute(task)
        }
    }

    companion object {
        private val executor = Executors.newFixedThreadPool(4)
        @Volatile private var instance: NavigationRepository? = null
        fun get(context: android.content.Context): NavigationRepository = instance ?: synchronized(this) {
            instance ?: NavigationRepository(context.applicationContext).also { instance = it }
        }
    }
}

sealed interface CommandOutcome {
    data class ServerAccepted(val target: CommandTarget) : CommandOutcome
    data class DeliveryUncertain(val target: CommandTarget) : CommandOutcome
}

fun userMessage(error: Exception, configuration: AppConfiguration): String {
    if (error is ClientException && error.statusCode == 403 && configuration.oauthSessionId != null && configuration.needsPermissionUpgrade) {
        val scopes = configuration.missingOAuthScopes.sorted().joinToString(", ")
        return "Reconnect Sunshine and approve $scopes access, then retry."
    }
    return userMessage(error)
}

fun userMessage(error: Exception): String = when (error) {
    is OAuthLoginRequired -> "Your Sunshine login has expired or been revoked. Sign in again in Settings."
    is ClientException -> when (error.statusCode) {
        401 -> "Your API token is expired or invalid. Update it in Settings."
        403 -> "This token or account lacks permission for this scooter. Check its sharing permissions."
        404 -> "The scooter was not found. Fetch and select it again in Settings."
        422 -> "The destination or command could not be accepted. Check the coordinates and scooter connectivity."
        429 -> "Too many requests. Wait a moment and retry."
        else -> "The server rejected the request. Check Settings and retry."
    }
    is ServerException -> "The server is unavailable. Please retry later."
    is IllegalArgumentException -> error.message ?: "Check the input and retry."
    is IOException -> "Unable to connect or resolve this location. Check the network and retry."
    else -> "The request failed. Please retry."
}
