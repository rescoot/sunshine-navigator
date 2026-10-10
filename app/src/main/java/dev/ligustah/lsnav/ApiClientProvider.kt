package dev.ligustah.lsnav

import dev.ligustah.lsnav.api.generated.apis.NavigationApi
import dev.ligustah.lsnav.api.generated.apis.ScootersApi
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ApiClientProvider(baseUrl: String, token: String?, transport: OkHttpClient = OkHttpClient()) {
    private val basePath = normalizeBaseUrl(baseUrl)
    private val cancelled = AtomicBoolean(false)
    val client: OkHttpClient = transport.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            if (cancelled.get()) throw IOException("Request cancelled")
            val request = chain.request().newBuilder()
            if (!token.isNullOrBlank()) request.header("Authorization", "Bearer ${token.trim()}")
            chain.proceed(request.build())
        }.build()

    fun cancel() {
        cancelled.set(true)
        client.dispatcher.cancelAll()
    }

    fun json(path: String, method: String = "GET", body: org.json.JSONObject? = null, requestKey: String? = null): org.json.JSONObject {
        val request = okhttp3.Request.Builder().url("$basePath/$path")
        if (requestKey != null) request.header("Idempotency-Key", requestKey)
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        request.method(method, payload)
        client.newCall(request.build()).execute().use { response ->
            val json = response.body?.string()?.takeIf(String::isNotBlank)?.let(::orgJson) ?: org.json.JSONObject()
            if (response.code >= 500) throw org.openapitools.client.infrastructure.ServerException("Sunshine is unavailable", response.code)
            if (!response.isSuccessful) throw NavigatorApiException(response.code, json.optJSONObject("error")?.optString("code"))
            return json
        }
    }

    private fun orgJson(value: String) = org.json.JSONObject(value)

    fun getNavigationApi() = NavigationApi(basePath = basePath, client = client)
    fun getScootersApi() = ScootersApi(basePath = basePath, client = client)

    companion object {
        fun normalizeBaseUrl(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS server URL")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Use an HTTPS server URL without credentials, a query, or a fragment"
            }
            val path = url.encodedPath.trimEnd('/')
            return url.newBuilder().encodedPath(if (path.endsWith("/api/v1")) path else "$path/api/v1")
                .build().toString().trimEnd('/')
        }
    }
}
