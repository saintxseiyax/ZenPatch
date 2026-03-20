package dev.zenpatch.manager.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zenpatch.manager.data.InstalledModule
import dev.zenpatch.manager.data.ModulesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ModulesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ModulesRepository(application)

    val modules: StateFlow<List<InstalledModule>> = repository.modules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            repository.refreshModules()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshModules()
        }
    }

    fun toggleModule(packageName: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleModule(packageName, enabled)
        }
    }
}
