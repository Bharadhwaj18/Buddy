package com.shrey.buddy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shrey.buddy.ui.AppShell
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.LoginScreen
import com.shrey.buddy.ui.theme.BuddyTheme
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so keep system bar icons light regardless of the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val container = (application as BuddyApp).container
        // Debug builds only: `adb shell am start ... --ez preview true` shows the app without signing in,
        // for UI checks on an emulator. Nothing syncs in this mode.
        val preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview", false)
        val startAt = intent.getStringExtra(EXTRA_OPEN)
        setContent {
            BuddyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val session by container.supabase.auth.sessionStatus.collectAsStateWithLifecycle()
                    // Coming back from another app, the SDK briefly re-checks/refreshes the session. Swapping the
                    // whole UI for a spinner then would reset the tab and scroll position — so once signed in,
                    // stay in the app until there's a real sign-out (NotAuthenticated).
                    var signedIn by rememberSaveable { mutableStateOf(false) }
                    if (session is SessionStatus.Authenticated) signedIn = true
                    if (session is SessionStatus.NotAuthenticated) signedIn = false
                    when {
                        preview || signedIn -> AppShell(viewModel { AppViewModel(container) }, startAt)
                        else -> AuthGate(session, container)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // An update or reboot stops Hey Buddy; switch it back on as soon as Buddy is open.
        com.shrey.buddy.voice.BuddyVoiceService.restoreIfWanted(this)
        runCatching { com.shrey.buddy.data.sync.DailyWorker.catchUp(this) }
    }

    companion object {
        /** Deep-link target from widgets / tiles: "chat", "tasks", "money", "health", "capture". */
        const val EXTRA_OPEN = "open"
    }
}

@androidx.compose.runtime.Composable
private fun AuthGate(session: SessionStatus, container: AppContainer) {
    when (session) {
        is SessionStatus.Initializing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> LoginScreen(container.supabase)
    }
}
