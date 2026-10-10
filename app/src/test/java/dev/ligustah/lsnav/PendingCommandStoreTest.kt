package dev.ligustah.lsnav

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingCommandStoreTest {
    @Test fun `pending target is private non-backup and omits credentials`() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val store = PendingCommandStore(application)
        val configuration = AppConfiguration("secret-token", "https://example.org", 7, "Scooter")
        val place = PlaceResult(Coordinates(52.52, 13.41), "Berlin")
        store.write(CommandTarget(configuration, place))
        val file = File(application.noBackupFilesDir, "pending-navigation.json")
        val content = file.readText()
        assertFalse(content.contains("secret-token"))
        assertEquals(PendingCommand(configuration.accountFingerprint(), 7, "Scooter", place), store.read())
        store.clear()
        assertEquals(null, store.read())
    }
}
