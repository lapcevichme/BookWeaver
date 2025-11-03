package com.lapcevichme.bookweaver.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lapcevichme.bookweaver.domain.usecase.books.GetActiveBookFlowUseCase
import com.lapcevichme.bookweaver.domain.usecase.theme.GetBookThemeColorUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Эта VM отвечает ТОЛЬКО за предоставление цвета темы
 * для текущей активной книги.
 */
@HiltViewModel
class BookThemeWrapperViewModel @Inject constructor(
    getActiveBookFlowUseCase: GetActiveBookFlowUseCase,
    getBookThemeColorUseCase: GetBookThemeColorUseCase
) : ViewModel() {

    private val defaultSeedColor = Color(0xFF00668B)

    @OptIn(ExperimentalCoroutinesApi::class)
    val themeSeedColor: StateFlow<Color> = getActiveBookFlowUseCase()
        .flatMapLatest { activeBookId ->
            getBookThemeColorUseCase(activeBookId)
        }
        .map { colorInt ->
            // Если цвет в кэше есть (для этой книги), используем его, иначе - цвет по умолчанию
            if (colorInt != null) Color(colorInt) else defaultSeedColor
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = defaultSeedColor
        )
}
