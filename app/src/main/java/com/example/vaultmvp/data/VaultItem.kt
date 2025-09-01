package com.example.vaultmvp.data

import kotlinx.serialization.Serializable

@Serializable
data class VaultItem(
    val id: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long? = null,
    val encryptedPath: String,
    val createdAt: Long,
    val driveId: String? = null,
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    val encVersion: Int = 1,
    val originalFileName: String? = null,
    val originalParentUri: String? = null
)

enum class SyncState { LOCAL_ONLY, SYNCED, DIRTY, REMOTE_ONLY }
