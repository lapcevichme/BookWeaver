package com.lapcevichme.bookweaver.features.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.model.ThemeSetting
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.books.GetLocalBooksUseCase
import com.lapcevichme.bookweaver.domain.usecase.settings.GetThemeSettingUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * ViewModel для определения стартового маршрута на основе реальных данных.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    getLocalBooksUseCase: GetLocalBooksUseCase,
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getThemeSettingUseCase: GetThemeSettingUseCase
) : ViewModel() {
    private val _startupState = MutableStateFlow<StartupState>(StartupState.Loading)
    val startupState = _startupState.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    /**
     * Предоставляет текущую настройку темы для UI (MainActivity)
     */
    val themeSetting = getThemeSettingUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeSetting.SYSTEM // Начинаем с системной по умолчанию
        )


    init {
        viewModelScope.launch {
            // Используем combine, чтобы получить последние данные из обоих источников
            combine(
                getLocalBooksUseCase(),
                getActiveBookFlowUseCase()
            ) { localBooks, activeBookId ->
                // Определяем состояние на основе полученных данных
                when {
                    localBooks.isEmpty() -> StartupState.NoBooks
                    activeBookId == null -> StartupState.GoToLibrary
                    else -> StartupState.GoToBookHub
                }
            }.collect { state ->
                // Обновляем состояние для UI
                _startupState.value = state
            }
        }
    }

    fun navigateToPlayerTab() {
        viewModelScope.launch {
            _navigationEvent.emit(NavigationEvent.NavigateToPlayer)
        }
    }
}

