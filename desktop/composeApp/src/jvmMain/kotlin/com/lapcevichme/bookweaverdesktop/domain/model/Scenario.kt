package com.lapcevichme.bookweaverdesktop.domain.model

/**
 * Доменная модель сценария, который состоит из списка реплик.
 */
data class Scenario(
    val replicas: List<Replica>
)

/**
 * Доменная модель, представляющая одну реплику (фразу) персонажа в сценарии.
 */
data class Replica(
    val speaker: String,
    val text: String
)
