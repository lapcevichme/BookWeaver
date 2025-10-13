package com.lapcevichme.bookweaverdesktop.ui.editor.manifest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.domain.model.BookArtifact
import com.lapcevichme.bookweaverdesktop.domain.usecase.GetBookArtifactUseCase
import com.lapcevichme.bookweaverdesktop.domain.usecase.UpdateBookArtifactUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

data class ManifestEditorUiState(
    val manifestContent: String? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isModified: Boolean = false,
    val errorMessage: String? = null
)

class ManifestEditorViewModel(
    private val bookName: String,
    private val getBookArtifactUseCase: GetBookArtifactUseCase,
    private val updateBookArtifactUseCase: UpdateBookArtifactUseCase,
    private val json: Json // Оставляем для pretty-print
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
            getBookArtifactUseCase(bookName, BookArtifact.MANIFEST)
                .onSuccess { rawJsonString ->
                    // Форматируем JSON для красивого отображения
                    val jsonElement = json.parseToJsonElement(rawJsonString)
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
            // Проверяем, что JSON валиден перед отправкой
            val validationResult = runCatching { json.parseToJsonElement(newContent) }

            if (validationResult.isFailure) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Некорректный JSON: ${validationResult.exceptionOrNull()?.message}") }
                return@launch
            }

            updateBookArtifactUseCase(bookName, BookArtifact.MANIFEST, newContent)
                .onSuccess {
                    originalContent = newContent
                    _uiState.update { it.copy(isSaving = false, isModified = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка сохранения: ${error.message}") }
                }
        }
    }

    fun markAsModified(currentContent: String) {
        _uiState.update { it.copy(isModified = currentContent != originalContent) }
    }
}
