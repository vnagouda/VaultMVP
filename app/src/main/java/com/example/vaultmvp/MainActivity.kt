// MainActivity.kt
package com.example.vaultmvp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.nav.Routes
import com.example.vaultmvp.ui.CameraCaptureScreen
import com.example.vaultmvp.ui.ImportProgressScreen
import com.example.vaultmvp.ui.RestoreProgressScreen
import com.example.vaultmvp.ui.VaultScreen
import com.example.vaultmvp.ui.ViewerScreen
import com.example.vaultmvp.ui.promptBiometric
import com.example.vaultmvp.util.LOG_TAG
import com.example.vaultmvp.vm.VaultViewModel

class MainActivity : FragmentActivity() {

    private val vm: VaultViewModel by viewModels {
        VaultViewModel.Factory(repo = VaultRepo(applicationContext))
    }


    private val REQUEST_RESTORE = 101
    private val REQUEST_PICK_DOCS = 100
    private var pendingRestoreItem: com.example.vaultmvp.data.VaultItem? = null

    // --- Auth state helpers ---
    private var lastAuthAt: Long = 0L
    private var isPromptShowing: Boolean = false
    private var pendingAction: (() -> Unit)? = null

    private companion object {
        private const val AUTH_COOLDOWN_MS = 1_500L
    }

    /** Gate any sensitive action behind biometrics. Also masks UI while prompting. */
    private fun requireAuthThen(
        title: String = "Unlock Vault",
        action: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (now - lastAuthAt <= AUTH_COOLDOWN_MS && vm.ui.value.unlocked) {
            action(); return
        }

        // If a prompt is already up, remember what the user wanted to do and bail.
        if (isPromptShowing) {
            pendingAction = action
            return
        }

        isPromptShowing = true
        vm.setUnlocked(false) // show mask during the sheet

        promptBiometric(
            activity = this,
            title = title,
            onSuccess = {
                lastAuthAt = System.currentTimeMillis()
                isPromptShowing = false
                vm.setUnlocked(true)

                // Prefer the most recent queued action (user may have tapped again while the sheet was up)
                val toRun = pendingAction ?: action
                pendingAction = null

                // ✅ FIX #2: execute AFTER the biometric sheet fully dismisses
                window.decorView.post {
                    if (!isFinishing) toRun()
                }
            },
            onError = {
                isPromptShowing = false
                pendingAction = null
                // Remains locked; the PrivacyShield stays visible.
            }
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(LOG_TAG, "Uncaught on ${t.name}", e)
        }

        super.onCreate(savedInstanceState)

        // Prevent screenshots / recents thumbnails
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()
                    val ui = vm.ui.collectAsStateWithLifecycle().value

                    Box(Modifier.fillMaxSize()) {

                        NavHost(
                            navController = nav,
                            startDestination = Routes.Home
                        ) {
                            composable(Routes.Home) {
                                VaultScreen(
                                    vm = vm,
                                    onPickFiles = {
                                        requireAuthThen("Unlock to import") {
                                            nav.navigate(Routes.ImportProgress)
                                            launchPicker()
                                        }
                                    },
                                    onRestore = { item ->
                                        requireAuthThen("Unlock to restore") {
                                            nav.navigate(Routes.RestoreProgress)
                                            launchRestore(item)
                                        }
                                    },
                                    onOpenItem = { item ->
                                        requireAuthThen("Unlock to view") {
                                            nav.navigate("viewer/${item.id}")
                                        }
                                    },
                                    onOpenCamera = { nav.navigate("camera") }
                                )
                            }

                            composable(Routes.ImportProgress) {
                                ImportProgressScreen(
                                    vm = vm,
                                    onBackToHome = { nav.popBackStack(Routes.Home, false) }
                                )
                            }

                            composable(
                                route = Routes.Viewer,
                                arguments = listOf(navArgument("itemId") { type = NavType.StringType })
                            ) { backStackEntry ->
                                val itemId = backStackEntry.arguments?.getString("itemId") ?: return@composable
                                ViewerScreen(
                                    vm = vm,
                                    itemId = itemId,
                                    onBack = {
                                        vm.closePreview()
                                        nav.popBackStack()
                                    }
                                )
                            }

                            composable(Routes.RestoreProgress) {
                                RestoreProgressScreen(
                                    vm = vm,
                                    onBackToHome = { nav.popBackStack() }
                                )
                            }

                            composable("camera") {
                                CameraCaptureScreen(vm = vm, onClose = { nav.popBackStack() })
                            }

                        }

                        // Full-screen mask while locked / prompting
                        PrivacyShield(visible = !ui.unlocked)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If locked (e.g., after onStop), prompt immediately.
        if (!vm.ui.value.unlocked) {
            requireAuthThen("Unlock Vault") { /* no-op */ }
        }
    }

    fun launchRestore(item: com.example.vaultmvp.data.VaultItem) {
        pendingRestoreItem = item
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_TITLE, item.displayName)
        }
        startActivityForResult(intent, REQUEST_RESTORE)
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_PICK_DOCS)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_RESTORE && resultCode == RESULT_OK) {
            val dest = data?.data ?: return
            pendingRestoreItem?.let { vm.exportToUriAndRemove(it, dest) }
            pendingRestoreItem = null
            return
        }

        if (requestCode == REQUEST_PICK_DOCS && resultCode == RESULT_OK && data != null) {
            val uris = buildList {
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
                } ?: data.data?.let { add(it) }
            }

            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) { /* already persisted or not needed */ }
            }

            if (uris.isNotEmpty()) vm.importAll(uris)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(LOG_TAG, "Activity onStop -> lock()")
        vm.lock() // sets unlocked=false -> mask shows automatically
    }
}

// Full-screen overlay used while locked or prompting
@Composable
private fun PrivacyShield(visible: Boolean) {
    if (!visible) return
    Surface(
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Locked — authenticate to continue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
