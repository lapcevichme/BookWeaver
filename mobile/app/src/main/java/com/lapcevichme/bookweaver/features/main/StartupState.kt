package com.lapcevichme.bookweaver.features.main

sealed class StartupState {
    object Loading : StartupState()
    object NoBooks : StartupState() // В приложении нет ни одной книги
    object GoToLibrary : StartupState() // Книги есть, но активная не выбрана
    object GoToBookHub : StartupState() // Есть активная книга
}