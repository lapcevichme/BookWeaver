package com.lapcevichme.bookweaver.features.main

sealed class NavigationEvent {
    object NavigateToPlayer : NavigationEvent()
}