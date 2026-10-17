package dev.undefinedteam.wearagent.presentation.chat.images

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import dev.undefinedteam.wearagent.presentation.theme.WearAgentTheme

/**
 * Hosts [ImagePickerScreen] in its own window. A Compose overlay shares the chat's
 * window and cannot be guaranteed to sit above the pager and drawer, and a `Dialog`
 * leaves the window background transparent on Wear — a separate window avoids both.
 * Results come back as URIs; the caller encodes them.
 */
class ImagePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAgentTheme {
                ImagePickerScreen(
                    onConfirm = { uris -> finishWith(uris) },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun finishWith(uris: List<android.net.Uri>) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_URIS, ArrayList(uris)),
        )
        finish()
    }

    companion object {
        const val EXTRA_URIS = "uris"

        /** The picked URIs from [data]; empty when the pick was cancelled. */
        fun readUris(data: Intent?): List<android.net.Uri> {
            if (data == null) return emptyList()
            return IntentCompat.getParcelableArrayListExtra(
                data,
                EXTRA_URIS,
                android.net.Uri::class.java
            )
                .orEmpty()
        }
    }
}
