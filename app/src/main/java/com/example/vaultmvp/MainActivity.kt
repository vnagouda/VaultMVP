// MainActivity.kt
package com.example.vaultmvp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.nav.Routes
import com.example.vaultmvp.ui.ImportProgressScreen
import com.example.vaultmvp.ui.RestoreProgressScreen
import com.example.vaultmvp.ui.VaultScreen
import com.example.vaultmvp.ui.ViewerScreen
import com.example.vaultmvp.ui.promptBiometric
import com.example.vaultmvp.vm.VaultViewModel

class MainActivity : FragmentActivity() {

    private val vm: VaultViewModel by viewModels {
        VaultViewModel.Factory(repo = VaultRepo(applicationContext))
    }
    private val REQUEST_RESTORE = 101
    private var pendingRestoreItem: com.example.vaultmvp.data.VaultItem? = null


    // 🔢 Use a tiny, safe request code well under 0xFFFF
    private val REQUEST_PICK_DOCS = 100

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    val nav = rememberNavController()

                    NavHost(
                        navController = nav,
                        startDestination = Routes.Home
                    ) {
                        composable(Routes.Home) {
                            VaultScreen(
                                vm = vm,
                                onPickFiles = {
                                    nav.navigate(Routes.ImportProgress)
                                    launchPicker()
                                },
                                onRestore = { item ->
                                    // Navigate to restore screen first, then open Create Document
                                    nav.navigate(Routes.RestoreProgress)
                                    launchRestore(item)
                                },
                                onOpenItem = { item -> nav.navigate("viewer/${item.id}") }
                            )
                        }

                        composable(Routes.ImportProgress) {
                            ImportProgressScreen(
                                vm = vm,
                                onBackToHome = {
                                    nav.popBackStack(Routes.Home, false)
                                }
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
                                onBackToHome = {
                                    // Prefer a simple one-step pop; the back stack is [Home, RestoreProgress]
                                    nav.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    fun launchRestore(item: com.example.vaultmvp.data.VaultItem) {
        pendingRestoreItem = item
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = item.mime.ifEmpty { "*/*" }
            putExtra(android.content.Intent.EXTRA_TITLE, item.displayName)
        }
        startActivityForResult(intent, REQUEST_RESTORE)
    }


    // MainActivity.kt

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            // Ensure we can persist read permission for long operations
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQUEST_PICK_DOCS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_RESTORE && resultCode == RESULT_OK) {
            val dest = data?.data ?: return
            pendingRestoreItem?.let { vm.exportToUriAndRemove(it, dest) } // now streams progress to screen
            pendingRestoreItem = null
            return
        }

        if (requestCode == REQUEST_PICK_DOCS && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(i).uri   // non-null
                    uris.add(uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }

            // Persist perms so import can run without the picker in foreground
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) { /* ignore (already persisted) */ }
            }

            if (uris.isNotEmpty()) {
                // Start the import right now (this will flip UI into 'in progress')
                vm.importAll(uris)
            }
        }
    }


    override fun onStop() {
        super.onStop()
        vm.lock()
    }
}
