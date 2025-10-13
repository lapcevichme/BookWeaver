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
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val manifestContent: String = "",
    val isModified: Boolean = false,
    val isJsonValid: Boolean = true,
    val errorMessage: String? = null
)

class ManifestEditorViewModel(
    private val bookName: String,
    private val getBookArtifactUseCase: GetBookArtifactUseCase,
    private val updateBookArtifactUseCase: UpdateBookArtifactUseCase,
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
            getBookArtifactUseCase(bookName, BookArtifact.MANIFEST)
                .onSuccess { rawJsonString ->
                    val jsonElement = json.parseToJsonElement(rawJsonString)
                    val prettyJson = json.encodeToString(JsonElement.serializer(), jsonElement)
                    originalContent = prettyJson
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            manifestContent = prettyJson,
                            isModified = false,
                            isJsonValid = true
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка загрузки: ${error.message}") }
                }
        }
    }

    fun onContentChange(newContent: String) {
        val isValid = runCatching { json.parseToJsonElement(newContent) }.isSuccess
        _uiState.update {
            it.copy(
                manifestContent = newContent,
                isModified = newContent != originalContent,
                isJsonValid = isValid
            )
        }
    }

    fun saveManifest() {
        viewModelScope.launch {
            val contentToSave = uiState.value.manifestContent
            if (!uiState.value.isJsonValid) return@launch

            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            updateBookArtifactUseCase(bookName, BookArtifact.MANIFEST, contentToSave)
                .onSuccess {
                    originalContent = contentToSave
                    _uiState.update {
                        it.copy(isSaving = false, isModified = false)
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка сохранения: ${error.message}") }
                }
        }
    }
}
