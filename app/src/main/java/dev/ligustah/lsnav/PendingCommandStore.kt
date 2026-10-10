package dev.ligustah.lsnav

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingCommand(
    val accountFingerprint: String,
    val scooterId: Long,
    val scooterName: String,
    val place: PlaceResult?
)

interface PendingCommandPersistence {
    suspend fun read(): PendingCommand?
    suspend fun write(target: CommandTarget)
    suspend fun clear()
}

class PendingCommandStore(context: Context) : PendingCommandPersistence {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "pending-navigation.json"))

    override suspend fun read(): PendingCommand? = withContext(Dispatchers.IO) {
        val input = try { file.openRead() } catch (_: java.io.FileNotFoundException) { return@withContext null }
        val json = JSONObject(input.bufferedReader().use { it.readText() })
        val place = if (!json.has("latitude")) null else {
            val coordinates = Coordinates(json.getDouble("latitude"), json.getDouble("longitude"))
            require(coordinates.isValid()) { "Invalid pending destination" }
            PlaceResult(coordinates, json.getString("label"))
        }
        PendingCommand(json.getString("accountFingerprint"), json.getLong("scooterId"), json.getString("scooterName"), place)
    }

    override suspend fun write(target: CommandTarget) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("accountFingerprint", target.configuration.accountFingerprint())
            .put("scooterId", target.configuration.scooterId)
            .put("scooterName", target.configuration.scooterName)
        target.place?.let {
            json.put("latitude", it.coordinates.latitude).put("longitude", it.coordinates.longitude).put("label", it.label)
        }
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}

fun AppConfiguration.accountFingerprint(): String {
    val identity = if (oauthSessionId != null) "oauth:$baseUrl:$oauthSessionId" else "token:$baseUrl:$token"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
