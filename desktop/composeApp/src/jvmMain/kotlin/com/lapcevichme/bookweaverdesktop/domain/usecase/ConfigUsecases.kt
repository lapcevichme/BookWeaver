package com.lapcevichme.bookweaverdesktop.domain.usecase

import com.lapcevichme.bookweaverdesktop.domain.repository.ConfigRepository

class GetConfigContentUseCase(private val repository: ConfigRepository) {
    suspend operator fun invoke() = repository.getConfigContent()
}

class SaveConfigContentUseCase(private val repository: ConfigRepository) {
    suspend operator fun invoke(content: String) = repository.saveConfigContent(content)
}
