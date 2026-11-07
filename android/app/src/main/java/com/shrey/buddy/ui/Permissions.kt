package com.shrey.buddy.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class PermissionGate(val granted: Boolean, val request: () -> Unit)

/**
 * Tracks whether the first [required] permissions are granted (re-checked on resume, e.g. after
 * the user flips them in Settings). Extra permissions (like notifications) are requested but optional.
 */
@Composable
fun rememberPermissionGate(
    permissions: Array<String>,
    required: Int = permissions.size,
    onResult: (granted: Boolean) -> Unit = {},
): PermissionGate {
    val context = LocalContext.current
    fun check() = permissions.take(required).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(check()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = check() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = check()
        onResult(granted)
    }
    return PermissionGate(granted) { launcher.launch(permissions) }
}
