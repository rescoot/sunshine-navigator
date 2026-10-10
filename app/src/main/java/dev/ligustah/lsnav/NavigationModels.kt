package dev.ligustah.lsnav

import java.util.Locale

data class Coordinates(val latitude: Double, val longitude: Double) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun display() = String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
}

data class PlaceResult(val coordinates: Coordinates, val label: String)
internal fun PlaceResult.needsLabel() = label.isBlank() || MapsIntentParser.parsePair(label.trim()) != null
data class ScooterImages(val front: String? = null, val left: String? = null, val right: String? = null)
data class ScooterModelInfo(
    val modelName: String? = null,
    val enginePowerRatingW: Int? = null,
    val speedClassKmh: Int? = null,
    val constructionYear: Int? = null,
    val color: String? = null,
    val fullName: String? = null
) {
    val displayName: String? get() = fullName?.takeIf(String::isNotBlank) ?: modelName?.takeIf(String::isNotBlank)
}
data class ScooterSummary(
    val id: Long,
    val name: String,
    val online: Boolean? = null,
    val state: String? = null,
    val vin: String? = null,
    val color: String? = null,
    val colorId: String? = null,
    val colorHex: String? = null,
    val model: ScooterModelInfo? = null,
    val licensePlate: String? = null,
    val lastSeenAt: String? = null,
    val images: ScooterImages = ScooterImages()
)
data class NavigationTarget(val place: PlaceResult? = null, val multiStopSupported: Boolean = false)
data class SavedPlace(val id: Long, val place: PlaceResult, val isFavorite: Boolean = true, val searchKey: String? = null)
data class PersonalPlaces(val favorites: List<SavedPlace> = emptyList(), val recent: List<SavedPlace> = emptyList())
data class FavoriteDraft(val label: String, val place: PlaceResult, val searchKey: String? = null)
data class CommandTarget(val configuration: AppConfiguration, val place: PlaceResult?)
data class AppProfile(val configuration: AppConfiguration = AppConfiguration(), val onboardingComplete: Boolean = false)
data class DestinationChange(val configuration: AppConfiguration)

data class RemoteData<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0
) {
    fun stale(ageMillis: Long) = !loading && System.currentTimeMillis() - updatedAt >= ageMillis
    fun refreshing() = copy(loading = true, error = null)
    fun failed(message: String) = copy(loading = false, error = message)
    companion object {
        fun <T> loaded(value: T) = RemoteData(value = value, updatedAt = System.currentTimeMillis())
    }
}
