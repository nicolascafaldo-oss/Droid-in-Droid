package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VmDao {
    @Query("SELECT * FROM vm_instances ORDER BY createdAt ASC")
    fun observeAllInstances(): Flow<List<VmInstanceEntity>>

    @Query("SELECT * FROM vm_instances WHERE id = :vmId LIMIT 1")
    suspend fun getInstanceById(vmId: String): VmInstanceEntity?

    @Query("SELECT COUNT(*) FROM vm_instances")
    suspend fun getInstanceCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstance(instance: VmInstanceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstances(instances: List<VmInstanceEntity>)

    @Update
    suspend fun updateInstance(instance: VmInstanceEntity)

    @Query("UPDATE vm_instances SET runState = :runState WHERE id = :vmId")
    suspend fun updateRunState(vmId: String, runState: String)

    @Query("UPDATE vm_instances SET widthPx = :widthPx, heightPx = :heightPx, dpi = :dpi, refreshRateHz = :refreshRateHz WHERE id = :vmId")
    suspend fun updateDisplayProfile(vmId: String, widthPx: Int, heightPx: Int, dpi: Int, refreshRateHz: Int)

    @Query("DELETE FROM vm_instances WHERE id = :vmId")
    suspend fun deleteInstanceById(vmId: String)

    @Query("SELECT * FROM vm_sandbox_files WHERE vmId = :vmId ORDER BY importedAt DESC")
    fun observeFilesForVm(vmId: String): Flow<List<VmSandboxFileEntity>>

    @Query("SELECT * FROM vm_sandbox_files ORDER BY importedAt DESC")
    fun observeAllFiles(): Flow<List<VmSandboxFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: VmSandboxFileEntity): Long

    @Query("DELETE FROM vm_sandbox_files WHERE id = :fileId")
    suspend fun deleteFileById(fileId: Long)

    @Query("DELETE FROM vm_sandbox_files WHERE vmId = :vmId")
    suspend fun deleteFilesForVm(vmId: String)

    @Query("SELECT * FROM guest_installed_apps ORDER BY isSystemApp DESC, installedAt ASC")
    fun observeAllGuestApps(): Flow<List<GuestInstalledAppEntity>>

    @Query("SELECT COUNT(*) FROM guest_installed_apps WHERE vmId = :vmId")
    suspend fun getGuestAppCount(vmId: String): Int

    @Query("SELECT * FROM guest_installed_apps WHERE vmId = :vmId AND packageName = :packageName LIMIT 1")
    suspend fun findGuestApp(vmId: String, packageName: String): GuestInstalledAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuestApp(app: GuestInstalledAppEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuestApps(apps: List<GuestInstalledAppEntity>)

    @Query("DELETE FROM guest_installed_apps WHERE id = :appId")
    suspend fun deleteGuestAppById(appId: Long)

    @Query("DELETE FROM guest_installed_apps WHERE vmId = :vmId")
    suspend fun deleteGuestAppsForVm(vmId: String)
}
