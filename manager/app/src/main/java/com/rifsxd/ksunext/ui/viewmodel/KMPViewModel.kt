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
import java.io.FileOutputStream

class KMPViewModel : ViewModel() {
    
    data class KMPInfo(
        val id: Int,
        val name: String,
        val version: String,
        val description: String,
        val state: String,
        val size: Long,
        val refCount: Int,
        val flags: List<String>
    )

    var kmpList by mutableStateOf<List<KMPInfo>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    var isNeedRefresh by mutableStateOf(true)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        errorMessage = null
    }

    fun fetchKMPList() {
        viewModelScope.launch {
            try {
                isRefreshing = true
                isLoading = true
                
                withContext(Dispatchers.IO) {
                    val modules = mutableListOf<KMPInfo>()
                    
                    // Get number of KMP modules
                    val count = Natives.kmpNums()
                    Log.d("KMPViewModel", "Found $count KMP modules")
                    
                    if (count > 0) {
                        // Get list of all KMP modules
                        val kmpListData = Natives.kmpList()
                        
                        kmpListData?.forEach { kmpData ->
                            // Get detailed info for each module
                            val info = Natives.kmpInfo(kmpData.id)
                            if (info != null) {
                                modules.add(
                                    KMPInfo(
                                        id = info.id,
                                        name = info.name.ifEmpty { "Unknown Module" },
                                        version = info.version.ifEmpty { "Unknown" },
                                        description = info.description.ifEmpty { "No description available" },
                                        state = info.state.ifEmpty { "unknown" },
                                        size = info.size,
                                        refCount = info.refCount,
                                        flags = info.flags
                                    )
                                )
                            }
                        }
                    }
                    
                    kmpList = modules.sortedBy { it.name }
                }
                
                isNeedRefresh = false
            } catch (e: Exception) {
                Log.e("KMPViewModel", "Failed to fetch KMP list", e)
                errorMessage = "Failed to fetch KMP modules: ${e.message}"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    fun installKMP(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                isLoading = true
                
                withContext(Dispatchers.IO) {
                    // Copy the file to a temporary location
                    val tempFile = File(context.cacheDir, "temp_kmp_${System.currentTimeMillis()}.ko")
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Load the KMP module
                    val result = Natives.kmpLoad(tempFile.absolutePath)
                    
                    // Clean up temp file
                    tempFile.delete()
                    
                    if (result) {
                        Log.d("KMPViewModel", "KMP module loaded successfully")
                        // Refresh the list to show the new module
                        fetchKMPList()
                    } else {
                        throw Exception("Failed to load KMP module")
                    }
                }
            } catch (e: Exception) {
                Log.e("KMPViewModel", "Failed to install KMP", e)
                errorMessage = "Failed to install KMP module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun unloadKMP(moduleId: Int) {
        viewModelScope.launch {
            try {
                isLoading = true
                
                withContext(Dispatchers.IO) {
                    val result = Natives.kmpUnload(moduleId)
                    
                    if (result) {
                        Log.d("KMPViewModel", "KMP module unloaded successfully")
                        // Refresh the list to remove the unloaded module
                        fetchKMPList()
                    } else {
                        throw Exception("Failed to unload KMP module")
                    }
                }
            } catch (e: Exception) {
                Log.e("KMPViewModel", "Failed to unload KMP", e)
                errorMessage = "Failed to unload KMP module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun controlKMP(moduleId: Int, operation: String, data: String = "") {
        viewModelScope.launch {
            try {
                isLoading = true
                
                withContext(Dispatchers.IO) {
                    val result = Natives.kmpControl(moduleId, operation, data)
                    
                    if (result) {
                        Log.d("KMPViewModel", "KMP control operation '$operation' successful")
                        // Refresh the list to reflect any changes
                        fetchKMPList()
                    } else {
                        throw Exception("KMP control operation failed")
                    }
                }
            } catch (e: Exception) {
                Log.e("KMPViewModel", "Failed to control KMP", e)
                errorMessage = "Failed to control KMP module: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    fun refreshKMPList() {
        isNeedRefresh = true
        fetchKMPList()
    }
}