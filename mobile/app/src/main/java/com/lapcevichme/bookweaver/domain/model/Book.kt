package com.lapcevichme.bookweaver.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val title: String,
    val author: String,
    val filePath: String // Путь к файлу на сервере, нужен для запроса аудио
)
