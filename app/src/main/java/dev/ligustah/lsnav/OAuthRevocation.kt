package dev.ligustah.lsnav

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal fun revokeOAuthTokens(root: String, clientId: String, tokens: List<String>, transport: OkHttpClient = OkHttpClient()): Boolean {
    val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
    var revoked = true
    for (token in tokens.distinct()) {
        try {
            val body = FormBody.Builder().add("client_id", clientId).add("token", token).build()
            client.newCall(Request.Builder().url("$root/oauth/revoke").post(body).build()).execute().use {
                if (!it.isSuccessful) revoked = false
            }
        } catch (_: Exception) { revoked = false }
    }
    return revoked
}
