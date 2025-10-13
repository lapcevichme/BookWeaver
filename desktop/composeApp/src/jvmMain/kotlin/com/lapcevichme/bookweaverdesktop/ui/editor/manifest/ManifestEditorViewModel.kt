package com.lapcevichme.bookweaverdesktop.ui.editor.manifest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.model.BookArtifact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

data class ManifestEditorUiState(
    val manifestContent: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isModified: Boolean = false,
    val errorMessage: String? = null
)

class ManifestEditorViewModel(
    private val bookName: String,
    private val apiClient: ApiClient,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManifestEditorUiState())
    val uiState = _uiState.asStateFlow()

    private var originalContent: String = ""

    init {
        loadManifest()
    }

    private fun loadManifest() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiClient.getBookArtifact(bookName, BookArtifact.MANIFEST)
                .onSuccess { jsonElement ->
                    // Форматируем JSON для красивого отображения
                    val prettyJson = json.encodeToString(JsonElement.serializer(), jsonElement)
                    originalContent = prettyJson
                    _uiState.update {
                        it.copy(isLoading = false, manifestContent = prettyJson, isModified = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка: ${error.message}") }
                }
        }
    }

    fun saveManifest(newContent: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                // Перед сохранением парсим строку обратно в JsonElement
                val jsonElement = json.parseToJsonElement(newContent).jsonObject
                apiClient.updateBookArtifact(bookName, BookArtifact.MANIFEST, jsonElement)
                    .onSuccess {
                        originalContent = newContent
                        _uiState.update { it.copy(isSaving = false, isModified = false) }
                    }
                    .onFailure { error ->
                        _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка сохранения: ${error.message}") }
                    }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Некорректный JSON: ${e.message}") }
            }
        }
    }

    fun markAsModified(currentContent: String) {
        _uiState.update { it.copy(isModified = currentContent != originalContent) }
    }
}
