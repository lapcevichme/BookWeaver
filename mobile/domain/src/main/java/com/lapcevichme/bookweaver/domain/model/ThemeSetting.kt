package com.lapcevichme.bookweaver.domain.model

/**
 * Модель, определяющая настройку темы приложения.
 */
enum class ThemeSetting(val storageKey: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system")
}