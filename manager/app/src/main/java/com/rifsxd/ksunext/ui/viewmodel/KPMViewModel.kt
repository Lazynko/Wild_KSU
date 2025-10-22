package com.rifsxd.ksunext.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rifsxd.ksunext.Natives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KPMViewModel : ViewModel() {

    data class KPMInfo(
        val id: Int,
        val name: String,
        val version: String,
        val description: String,
        val state: String,
        val size: Long,
        val refCount: Int,
        val flags: List<String>
    )

    var kpmList by mutableStateOf<List<KPMInfo>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    var isNeedRefresh by mutableStateOf(true)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        fetchKPMList()
    }

    fun fetchKPMList() {
        viewModelScope.launch {
            isLoading = true
            isRefreshing = true
            try {
                withContext(Dispatchers.IO) {
                    val modules = mutableListOf<KPMInfo>()

                    // Get number of KPM modules
                    val count = Natives.getKpmModuleCount()
                    Log.d("KPMViewModel", "Found $count Kernel Patch modules")

                    if (count > 0) {
                        // Get list of all KPM modules
                        val kpmListData = Natives.getKpmModuleList()

                        kpmListData?.forEach { moduleName ->
                            // Get detailed info for each module
                            val info = Natives.getKpmModuleInfo(moduleName)
                            if (info != null) {
                                modules.add(
                                    KPMInfo(
                                        id = modules.size + 1, // Generate ID based on index
                                        name = info.name,
                                        version = info.version,
                                        description = info.description,
                                        state = if (info.state == 1) "loaded" else "unloaded",
                                        size = info.size,
                                        refCount = info.refCount,
                                        flags = emptyList() // TODO: Parse flags if needed
                                    )
                                )
                            }
                        }
                    }

                    kpmList = modules.sortedBy { it.name }
                }
                isNeedRefresh = false
            } catch (e: Exception) {
                Log.e("KPMViewModel", "Failed to fetch Kernel Patch modules", e)
                errorMessage = "Failed to fetch Kernel Patch modules: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    fun installKPM(uri: Uri, context: Context) {
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    // Copy file to temporary location
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val tempFile = File(context.cacheDir, "temp_kpm_${System.currentTimeMillis()}.ko")

                    inputStream?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Load the KPM module
                    val result = Natives.loadKpmModule(tempFile.absolutePath)

                    // Clean up temp file
                    tempFile.delete()

                    if (result) {
                        Log.d("KPMViewModel", "Kernel Patch module loaded successfully")
                        isNeedRefresh = true
                        fetchKPMList()
                    } else {
                        throw Exception("Failed to load Kernel Patch module")
                    }
                }
            } catch (e: Exception) {
                Log.e("KPMViewModel", "Failed to install Kernel Patch module", e)
                errorMessage = "Failed to install Kernel Patch module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun unloadKPM(moduleId: Int) {
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    // Find module by ID
                    val module = kpmList.find { it.id == moduleId }
                    val result = if (module != null) {
                        Natives.unloadKpmModule(module.name)
                    } else {
                        false
                    }

                    if (result) {
                        Log.d("KPMViewModel", "Kernel Patch module unloaded successfully")
                        isNeedRefresh = true
                        fetchKPMList()
                    } else {
                        throw Exception("Failed to unload Kernel Patch module")
                    }
                }
            } catch (e: Exception) {
                Log.e("KPMViewModel", "Failed to unload Kernel Patch module", e)
                errorMessage = "Failed to unload Kernel Patch module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun controlKPM(moduleId: Int, operation: String, data: String = "") {
        viewModelScope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    // Find module by ID
                    val module = kpmList.find { it.id == moduleId }
                    val result = if (module != null) {
                        Natives.controlKpmModule(module.name, operation.hashCode(), data.hashCode().toLong())
                    } else {
                        -1L
                    }

                    if (result >= 0) {
                        Log.d("KPMViewModel", "Kernel Patch module control operation '$operation' successful")
                        isNeedRefresh = true
                        fetchKPMList()
                    } else {
                        throw Exception("Kernel Patch module control operation failed")
                    }
                }
            } catch (e: Exception) {
                Log.e("KPMViewModel", "Failed to control Kernel Patch module", e)
                errorMessage = "Failed to control Kernel Patch module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun refreshKPMList() {
        isNeedRefresh = true
        fetchKPMList()
    }

    fun clearError() {
        errorMessage = null
    }
}