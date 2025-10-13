package com.lapcevichme.bookweaverdesktop.ui.editor.scenario

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaverdesktop.domain.model.Replica
import com.lapcevichme.bookweaverdesktop.domain.model.Scenario
import com.lapcevichme.bookweaverdesktop.domain.usecase.GetChapterScenarioUseCase
import com.lapcevichme.bookweaverdesktop.domain.usecase.UpdateChapterScenarioUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.*

data class UiReplica(
    val id: String,
    val speaker: String,
    val text: String
)

data class ScenarioEditorUiState(
    val replicas: List<UiReplica> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

class ScenarioEditorViewModel(
    private val bookName: String,
    private val volume: Int,
    private val chapter: Int,
    private val getChapterScenarioUseCase: GetChapterScenarioUseCase,
    private val updateChapterScenarioUseCase: UpdateChapterScenarioUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScenarioEditorUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadScenario()
    }

    fun loadScenario() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            getChapterScenarioUseCase(bookName, volume, chapter)
                .onSuccess { scenario ->
                    val uiReplicas = scenario.replicas.map { domainReplica ->
                        UiReplica(
                            id = UUID.randomUUID().toString(),
                            speaker = domainReplica.speaker,
                            text = domainReplica.text
                        )
                    }
                    _uiState.update { it.copy(isLoading = false, replicas = uiReplicas) }
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

            val domainReplicas = currentUiReplicas.map { uiReplica ->
                Replica(speaker = uiReplica.speaker, text = uiReplica.text)
            }
            val scenarioToSave = Scenario(domainReplicas)

            updateChapterScenarioUseCase(bookName, volume, chapter, scenarioToSave)
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
