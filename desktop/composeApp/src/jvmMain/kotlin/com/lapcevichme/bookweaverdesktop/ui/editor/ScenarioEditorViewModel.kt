package com.lapcevichme.bookweaverdesktop.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.model.ChapterArtifact
import com.lapcevichme.bookweaverdesktop.model.Replica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.util.*

/**
 * UI-модель: Обертка над серверной моделью Replica,
 * добавляющая уникальный ID для стабильной работы списков в Compose.
 */
data class UiReplica(
    val id: String,
    val speaker: String,
    val text: String
)

data class ScenarioEditorUiState(
    val replicas: List<UiReplica> = emptyList(), // Используем новую UI-модель
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

class ScenarioEditorViewModel(
    private val bookName: String,
    private val volume: Int,
    private val chapter: Int,
    private val apiClient: ApiClient,
    private val json: Json
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScenarioEditorUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadScenario()
    }

    fun loadScenario() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            apiClient.getChapterArtifact(bookName, volume, chapter, ChapterArtifact.SCENARIO)
                .onSuccess { jsonElement ->
                    try {
                        val serverReplicas = json.decodeFromString<List<Replica>>(jsonElement.toString())
                        val uiReplicas = serverReplicas.map {
                            UiReplica(id = UUID.randomUUID().toString(), speaker = it.speaker, text = it.text)
                        }
                        _uiState.update { it.copy(isLoading = false, replicas = uiReplicas) }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка парсинга сценария: ${e.message}") }
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка загрузки: ${error.message}") }
                }
        }
    }

    fun saveScenario() {
        val currentUiReplicas = uiState.value.replicas
        if (currentUiReplicas.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val serverReplicas = currentUiReplicas.map { Replica(speaker = it.speaker, text = it.text) }

            val contentToSave = json.encodeToJsonElement(serverReplicas)

            // ИСПРАВЛЕНО: Вызываем новый метод для обновления артефакта главы
            apiClient.updateChapterArtifact(
                bookName = bookName,
                volumeNum = volume,
                chapterNum = chapter,
                artifactName = ChapterArtifact.SCENARIO,
                content = contentToSave
            )
                .onSuccess {
                    _uiState.update { it.copy(isSaving = false) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка сохранения: ${error.message}") }
                }
        }
    }

    fun updateReplicaText(replicaId: String, newText: String) {
        _uiState.update { currentState ->
            val updatedReplicas = currentState.replicas.map {
                if (it.id == replicaId) it.copy(text = newText) else it
            }
            currentState.copy(replicas = updatedReplicas)
        }
    }
}

