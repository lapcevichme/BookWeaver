package com.lapcevichme.bookweaver.data.repository

import com.lapcevichme.bookweaver.data.network.Book
import com.lapcevichme.bookweaver.data.network.WsBookList
import com.lapcevichme.bookweaver.data.network.WsMessage
import com.lapcevichme.bookweaver.data.network.WsRequestBookList
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import com.lapcevichme.network.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton



@Singleton
class BookRepositoryImpl @Inject constructor(
    private val serverRepository: ServerRepository, // Используем "глупый" репозиторий из библиотеки
    private val json: Json // Получаем парсер через Hilt
) : BookRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    override val books = _books.asStateFlow()

    init {
        // Слушаем статус соединения, чтобы автоматически запросить книги
        serverRepository.connectionStatus
            .filter { it.startsWith("Подключено") }
            .onEach {
                requestBookList()
            }
            .launchIn(scope)

        // Слушаем входящие сообщения и парсим только те, что нам нужны
        serverRepository.incomingMessages
            .onEach { messageString ->
                handleIncomingMessage(messageString)
            }
            .launchIn(scope)
    }

    override fun requestBookList() {
        scope.launch {
            val request = WsRequestBookList
            val requestString = json.encodeToString(WsMessage.serializer(), request)
            serverRepository.sendMessage(requestString)
        }
    }

    private fun handleIncomingMessage(messageString: String) {
        try {
            when (val message = json.decodeFromString(WsMessage.serializer(), messageString)) {
                is WsBookList -> {
                    _books.value = message.books
                }
                // Здесь можно обрабатывать другие сообщения, относящиеся к книгам
                else -> { /* Игнорируем сообщения, которые нас не касаются */ }
            }
        } catch (e: Exception) {
            // Log error
        }
    }
}
