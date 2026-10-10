package dev.ligustah.lsnav

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = runCatching { MapsIntentParser.extractIntentText(intent) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= MapsIntentParser.MAX_INPUT_LENGTH }
        if (shared != null) {
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shared)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }
}
