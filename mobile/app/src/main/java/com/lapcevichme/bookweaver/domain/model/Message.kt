package com.lapcevichme.bookweaver.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class WsMessage

@Serializable
@SerialName("request_book_list")
// --- ИЗМЕНЕНИЕ 2: Убираем `override val type` ---
object WsRequestBookList : WsMessage()

@Serializable
@SerialName("book_list")
data class WsBookList(
    val books: List<Book>
) : WsMessage()

@Serializable
@SerialName("request_audio")
data class WsRequestAudio(
    val filePath: String
) : WsMessage()