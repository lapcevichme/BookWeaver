package com.lapcevichme.bookweaver.features.library

sealed class LibraryEvent {
    data class BookSelected(val bookId: String) : LibraryEvent()
    object Refresh : LibraryEvent()
}

sealed class NavigationEvent {
    object NavigateToBookHub : NavigationEvent()
}