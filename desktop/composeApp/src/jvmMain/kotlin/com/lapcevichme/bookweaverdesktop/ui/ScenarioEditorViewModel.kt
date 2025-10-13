package com.lapcevichme.bookweaverdesktop.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.model.ChapterArtifactName
import com.lapcevichme.bookweaverdesktop.model.Replica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ServerReplica(val speaker: String, val text: String)

data class ScenarioEditorUiState(
    // Храним напрямую список реплик
    val replicas: List<Replica> = emptyList(),
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
            apiClient.getChapterArtifact(bookName, volume, chapter, ChapterArtifactName.SCENARIO)
                .onSuccess { content ->
                    try {
                        // Парсим JSON как список объектов ServerReplica
                        val serverReplicas = json.decodeFromString<List<ServerReplica>>(content.toString())

                        // Добавляем уникальные ID для UI
                        val uiReplicas = serverReplicas.map {
                            Replica(id = UUID.randomUUID().toString(), speaker = it.speaker, text = it.text)
                        }

                        _uiState.update { it.copy(isLoading = false, replicas = uiReplicas) }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(isLoading = false, errorMessage = "Ошибка парсинга сценария: ${e.message}") }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Ошибка загрузки сценария: ${error.message}")
                    }
                }
        }
    }

    fun saveScenario() {
        val currentReplicas = uiState.value.replicas
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                // Перед отправкой убираем UI-специфичное поле ID
                val serverReplicas = currentReplicas.map { ServerReplica(speaker = it.speaker, text = it.text) }
                val scenarioJsonString = json.encodeToString(serverReplicas)

                // apiClient.updateChapterArtifact(bookName, volume, chapter, ChapterArtifactName.SCENARIO, scenarioJsonString)
                //     .onSuccess { _uiState.update { it.copy(isSaving = false) } }
                //     .onFailure { error -> _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка: ${error.message}") } }
                println("Сохранение: $scenarioJsonString")
                kotlinx.coroutines.delay(1000)
                _uiState.update { it.copy(isSaving = false) }

            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, errorMessage = "Ошибка сериализации: ${e.message}") }
            }
        }
    }

    fun updateReplicaText(replicaId: String, newText: String) {
        val updatedReplicas = uiState.value.replicas.map {
            if (it.id == replicaId) it.copy(text = newText) else it
        }
        _uiState.update { it.copy(replicas = updatedReplicas) }
    }
}

