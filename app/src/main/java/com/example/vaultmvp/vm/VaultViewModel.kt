package com.example.vaultmvp.vm

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vaultmvp.data.VaultItem
import com.example.vaultmvp.data.VaultRepo
import com.example.vaultmvp.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Preview {
    data class ImageFile(val file: File) : Preview
    data class Pdf(val file: File) : Preview
    data class Video(val file: File) : Preview
    data class Unsupported(val reason: String) : Preview
}

data class ImportUi(
    val phase: ImportPhase = ImportPhase.Idle,
    val fileName: String? = null,
    val progress: Float? = null,
    val error: String? = null
)
enum class ImportPhase { Idle, Encrypting, Finalizing, Success, Cancelled, Error }

data class ExportUi(
    val phase: ExportPhase = ExportPhase.Idle,
    val fileName: String? = null,
    val progress: Float? = null,
    val error: String? = null
)
enum class ExportPhase { Idle, Decrypting, Finalizing, Success, Cancelled, Error }

data class UiState(
    val unlocked: Boolean = false,
    val items: List<VaultItem> = emptyList(),
    val preview: Preview? = null,
    val import: ImportUi = ImportUi(),
    val export: ExportUi = ExportUi(),
    val openProgress: Float? = null
)

class VaultViewModel(
    val repo: VaultRepo
) : ViewModel() {

    private val _ui = MutableStateFlow(UiState(items = repo.loadItems()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    @Volatile private var cancelEncrypt = false
    @Volatile private var cancelExport = false

    private val MIN_ENCRYPT_MS = 900L
    private val MIN_FINALIZE_MS = 700L
    private val MIN_DECRYPT_MS = 900L

    fun setUnlocked(unlocked: Boolean) {
        Log.d(LOG_TAG, "VM setUnlocked=$unlocked")
        _ui.update { it.copy(unlocked = unlocked) }
    }

    fun requestCancelEncryption() { cancelEncrypt = true }
    fun requestCancelExport() { cancelExport = true }

    fun clearImportUi() { _ui.update { it.copy(import = ImportUi()) } }
    fun clearExportUi() { _ui.update { it.copy(export = ExportUi()) } }

    // ---------------- Import (encrypt) ----------------
    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var currentItems = repo.loadItems()
            cancelEncrypt = false

            uris.forEachIndexed { index, u ->
                _ui.update { it.copy(import = ImportUi(phase = ImportPhase.Encrypting, fileName = "Preparing…", progress = null)) }
                val displayName = repo.peekDisplayName(u) ?: "Encrypting file"
                _ui.update { it.copy(import = it.import.copy(fileName = displayName)) }

                val startAt = SystemClock.elapsedRealtime()
                var lastBucket = -1
                var added: VaultItem? = null

                try {
                    added = repo.importAndEncrypt(
                        uri = u,
                        onProgress = { p ->
                            val clamped = p.coerceIn(0f, 1f)
                            val elapsed = SystemClock.elapsedRealtime() - startAt
                            val allowed = ((elapsed.toFloat() / MIN_ENCRYPT_MS).coerceIn(0f, 1f) * 0.97f)
                            val gated = if (clamped <= allowed) clamped else allowed

                            val bucket = (gated * 100).toInt() / 10
                            if (bucket != lastBucket) {
                                lastBucket = bucket
                                Log.d(LOG_TAG, "VM progress $displayName: ${bucket * 10}%")
                            }
                            _ui.update { st -> st.copy(import = st.import.copy(progress = gated)) }
                        },
                        isCancelled = { cancelEncrypt }
                    )

                    val encElapsed = SystemClock.elapsedRealtime() - startAt
                    if (encElapsed < MIN_ENCRYPT_MS) delay(MIN_ENCRYPT_MS - encElapsed)

                    if (cancelEncrypt) {
                        added?.let { safeDelete(it.encryptedPath) }
                        _ui.update { it.copy(import = ImportUi(phase = ImportPhase.Cancelled, fileName = displayName)) }
                        delay(400); _ui.update { it.copy(import = ImportUi()) }
                        return@launch
                    }

                    _ui.update { it.copy(import = it.import.copy(phase = ImportPhase.Finalizing, progress = null)) }
                    val finalizeStart = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - finalizeStart < MIN_FINALIZE_MS) {
                        if (cancelEncrypt) {
                            added?.let { safeDelete(it.encryptedPath) }
                            _ui.update { it.copy(import = ImportUi(phase = ImportPhase.Cancelled, fileName = displayName)) }
                            delay(400); _ui.update { it.copy(import = ImportUi()) }
                            return@launch
                        }
                        delay(50)
                    }

                    checkNotNull(added)
                    currentItems = currentItems + added
                    repo.saveItems(currentItems)
                    repo.deleteOriginal(u)

                    _ui.update { it.copy(items = currentItems, import = ImportUi(phase = ImportPhase.Success, fileName = added.displayName, progress = 1f)) }
                    delay(600)
                    _ui.update { it.copy(import = ImportUi()) }

                    if (cancelEncrypt) return@launch
                } catch (_: InterruptedException) {
                    added?.let { safeDelete(it.encryptedPath) }
                    _ui.update { it.copy(import = ImportUi(phase = ImportPhase.Cancelled, fileName = displayName)) }
                    delay(400); _ui.update { it.copy(import = ImportUi()) }
                    return@launch
                } catch (t: Throwable) {
                    Log.e(LOG_TAG, "VM import failed for $displayName", t)
                    added?.let { safeDelete(it.encryptedPath) }
                    _ui.update { it.copy(import = ImportUi(phase = ImportPhase.Error, fileName = displayName, error = t.message)) }
                    delay(800); _ui.update { it.copy(import = ImportUi()) }
                    return@launch
                }
            }
            _ui.update { it.copy(items = repo.loadItems()) }
        }
    }

    // ---------------- Restore (export) ----------------
    fun exportToUriAndRemove(item: VaultItem, dest: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            cancelExport = false
            _ui.update { it.copy(export = ExportUi(phase = ExportPhase.Decrypting, fileName = item.displayName, progress = 0f)) }

            val startAt = SystemClock.elapsedRealtime()
            var lastBucket = -1

            val ok = try {
                repo.exportToUriAndRemove(
                    item = item,
                    dest = dest,
                    onProgress = { p ->
                        val progress = p?.coerceIn(0f, 1f)
                        if (progress != null) {
                            val elapsed = SystemClock.elapsedRealtime() - startAt
                            val allowed = ((elapsed.toFloat() / MIN_DECRYPT_MS).coerceIn(0f, 1f) * 0.97f)
                            val gated = if (progress <= allowed) progress else allowed

                            val bucket = (gated * 100).toInt() / 10
                            if (bucket != lastBucket) {
                                lastBucket = bucket
                                Log.d(LOG_TAG, "VM restore progress ${item.displayName}: ${bucket * 10}%")
                            }
                            _ui.update { st -> st.copy(export = st.export.copy(progress = gated)) }
                        } else {
                            _ui.update { st -> st.copy(export = st.export.copy(progress = null)) }
                        }
                    },
                    isCancelled = { cancelExport }
                )
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "VM exportToUriAndRemove failed for id=${item.id}", t)
                false
            }

            val decElapsed = SystemClock.elapsedRealtime() - startAt
            if (decElapsed < MIN_DECRYPT_MS) delay(MIN_DECRYPT_MS - decElapsed)

            if (!ok) {
                if (cancelExport) {
                    _ui.update { it.copy(export = ExportUi(phase = ExportPhase.Cancelled, fileName = item.displayName)) }
                    delay(400); _ui.update { it.copy(export = ExportUi()) }
                } else {
                    _ui.update { it.copy(export = ExportUi(phase = ExportPhase.Error, fileName = item.displayName, error = "Restore failed")) }
                    delay(800); _ui.update { it.copy(export = ExportUi()) }
                }
                _ui.update { it.copy(items = repo.loadItems()) }
                return@launch
            }

            _ui.update { it.copy(export = it.export.copy(phase = ExportPhase.Finalizing, progress = null)) }
            val finalizeStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - finalizeStart < MIN_FINALIZE_MS) {
                if (cancelExport) {
                    _ui.update { it.copy(export = ExportUi(phase = ExportPhase.Cancelled, fileName = item.displayName)) }
                    delay(400); _ui.update { it.copy(export = ExportUi()) }
                    _ui.update { it.copy(items = repo.loadItems()) }
                    return@launch
                }
                delay(50)
            }

            val itemsNow = repo.loadItems()
            _ui.update {
                it.copy(
                    items = itemsNow,
                    export = ExportUi(phase = ExportPhase.Success, fileName = item.displayName, progress = 1f)
                )
            }
        }
    }

    // ---------------- Open (preview) with progress ----------------
    fun openAfterAuth(item: VaultItem) {
        Log.d(LOG_TAG, "VM openAfterAuth id=${item.id} name=${item.displayName} mime=${item.mime}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _ui.update { it.copy(openProgress = 0f, preview = null) }
                val startAt = SystemClock.elapsedRealtime()
                var lastBucket = -1

                val file = repo.decryptToTempFileProgress(
                    item = item,
                    onProgress = { p ->
                        val progress = p?.coerceIn(0f, 1f)
                        if (progress != null) {
                            val elapsed = SystemClock.elapsedRealtime() - startAt
                            val allowed = ((elapsed.toFloat() / MIN_DECRYPT_MS).coerceIn(0f, 1f) * 0.97f)
                            val gated = if (progress <= allowed) progress else allowed

                            val bucket = (gated * 100).toInt() / 10
                            if (bucket != lastBucket) {
                                lastBucket = bucket
                                Log.d(LOG_TAG, "VM open progress ${item.displayName}: ${bucket * 10}%")
                            }
                            _ui.update { st -> st.copy(openProgress = gated) }
                        } else {
                            _ui.update { st -> st.copy(openProgress = null) }
                        }
                    }
                )

                val decElapsed = SystemClock.elapsedRealtime() - startAt
                if (decElapsed < MIN_DECRYPT_MS) delay(MIN_DECRYPT_MS - decElapsed)

                val preview = when {
                    item.mime.startsWith("image/") -> Preview.ImageFile(file)
                    item.mime == "application/pdf" -> Preview.Pdf(file)
                    item.mime.startsWith("video/") -> Preview.Video(file)
                    else -> Preview.Unsupported("Office preview not supported in MVP")
                }
                _ui.update { it.copy(preview = preview, openProgress = null) }
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "VM openAfterAuth decrypt failed", t)
                _ui.update { it.copy(preview = Preview.Unsupported("Failed to open: ${t.message}"), openProgress = null) }
            }
        }
    }

    fun closePreview() {
        Log.d(LOG_TAG, "VM closePreview()")
        (_ui.value.preview as? Preview.ImageFile)?.file?.delete()
        (_ui.value.preview as? Preview.Pdf)?.file?.delete()
        (_ui.value.preview as? Preview.Video)?.file?.delete()
        _ui.update { it.copy(preview = null, openProgress = null) }
    }

    fun lock() {
        Log.d(LOG_TAG, "VM lock()")
        closePreview()
        _ui.update { it.copy(unlocked = false) }
    }

    fun rename(itemId: String, newName: String) {
        Log.d(LOG_TAG, "VM rename id=$itemId -> $newName")
        viewModelScope.launch(Dispatchers.IO) {
            val updated = repo.renameItem(itemId, newName)
            _ui.update { it.copy(items = updated) }
        }
    }

    private fun safeDelete(path: String) {
        try { File(path).delete() } catch (_: Exception) {}
    }

    class Factory(private val repo: VaultRepo) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(VaultViewModel::class.java))
            return VaultViewModel(repo) as T
        }
    }
}
