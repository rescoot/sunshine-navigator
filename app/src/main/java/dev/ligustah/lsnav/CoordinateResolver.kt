package dev.ligustah.lsnav

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface LocationLookup {
    suspend fun search(query: String): List<PlaceResult>
}

class AndroidLocationLookup(private val context: Context) : LocationLookup {
    override suspend fun search(query: String): List<PlaceResult> {
        if (!Geocoder.isPresent()) throw IOException("Address search is unavailable on this device")
        val geocoder = Geocoder(context, Locale.getDefault())
        val addresses = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine<List<Address>> { continuation ->
                geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        if (continuation.isActive) continuation.resume(addresses)
                    }
                    override fun onError(errorMessage: String?) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Address search failed"))
                    }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { geocoder.getFromLocationName(query, 5) ?: emptyList() }
        }
        return addresses.mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
            val coords = Coordinates(address.latitude, address.longitude)
            if (!coords.isValid()) return@mapNotNull null
            val label = (0..address.maxAddressLineIndex).mapNotNull(address::getAddressLine)
                .joinToString(", ").ifBlank { address.featureName ?: "${coords.latitude}, ${coords.longitude}" }
            PlaceResult(coords, label)
        }
    }
}

class CoordinateResolver(
    private val lookup: LocationLookup,
    client: OkHttpClient = OkHttpClient()
) {
    constructor(context: Context) : this(AndroidLocationLookup(context.applicationContext))

    private val httpClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun resolve(text: String): List<PlaceResult> {
        require(text.length <= MapsIntentParser.MAX_INPUT_LENGTH) { "The shared text is too long" }
        val input = MapsIntentParser.extractUrlOrText(text)
        MapsIntentParser.parsePair(input)?.let { return listOf(PlaceResult(it, input)) }
        var uri = Uri.parse(input)
        MapsIntentParser.extractCoordinates(uri)?.let { coordinates ->
            val label = MapsIntentParser.extractPlaceName(uri)?.takeIf(String::isNotBlank) ?: coordinates.display()
            return listOf(PlaceResult(coordinates, label))
        }
        if (isShortLink(uri)) {
            val expanded = expandShortLink(input)
            uri = Uri.parse(expanded)
            MapsIntentParser.extractCoordinates(uri)?.let { coordinates ->
                val label = MapsIntentParser.extractPlaceName(uri)?.takeIf(String::isNotBlank) ?: coordinates.display()
                return listOf(PlaceResult(coordinates, label))
            }
        }
        val name = MapsIntentParser.extractPlaceName(uri)
        val isUrl = uri.scheme != null || input.contains("://") || uri.host != null
        val query = name ?: input.takeIf { !isUrl && it.isNotBlank() }
        return if (query == null) emptyList() else lookup.search(query)
    }

    suspend fun search(query: String): List<PlaceResult> {
        if (query.isBlank()) return emptyList()
        return resolve(query)
    }

    private fun isShortLink(uri: Uri): Boolean = uri.scheme == "https" &&
        (uri.host == "maps.app.goo.gl" || (uri.host == "goo.gl" && uri.path?.startsWith("/maps") == true))

    private fun isTrustedRedirect(host: String): Boolean = host in setOf(
        "maps.app.goo.gl", "goo.gl", "google.com", "www.google.com", "maps.google.com",
        "www.google.co.uk", "www.google.de", "www.google.fr", "www.google.nl"
    )

    private suspend fun expandShortLink(input: String): String {
        var url = input
        repeat(5) {
            httpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
                if (response.code !in listOf(301, 302, 303, 307, 308)) {
                    if (!response.isSuccessful) throw IOException("The Maps link could not be opened")
                    return url
                }
                val next = response.header("Location")?.let { response.request.url.resolve(it) }
                    ?: throw IOException("The Maps link has an invalid redirect")
                if (!next.isHttps || next.username.isNotEmpty() || next.password.isNotEmpty() || !isTrustedRedirect(next.host)) {
                    throw IOException("The Maps link redirects to an unsupported site")
                }
                url = next.toString()
                if (!isShortLink(Uri.parse(url))) return url
            }
        }
        throw IOException("The Maps link has too many redirects")
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
