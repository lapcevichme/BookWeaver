package com.lapcevichme.bookweaver.data.repository.mock

import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Это моковая реализация репозитория для разработки и тестирования UI.
 * Он не делает реальных сетевых запросов, а просто возвращает
 * заранее заготовленный список книг с небольшой задержкой.
 */
class MockBookRepository @Inject constructor() : BookRepository {

    // Используем StateFlow, чтобы UI мог на него подписаться, как и на настоящий репозиторий.
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    override val books: StateFlow<List<Book>> = _books

    init {
        // При создании репозитория сразу "загружаем" моковые данные
        requestBookList()
    }

    override fun requestBookList() {
        // Здесь не будет сетевого запроса.
        // Вместо этого мы просто обновляем наш StateFlow.
        // Добавляем задержку, чтобы имитировать загрузку из сети.
        GlobalScope.launch {
            delay(1500) // Имитируем загрузку в 1.5 секунды
            _books.value = mockBooks
        }
    }

    // Список наших "фейковых" книг
    private val mockBooks = listOf(
        Book(
            title = "Хоббит, или Туда и обратно",
            author = "Дж. Р. Р. Толкин",
            filePath = "hobbit.epub"
        ),
        Book(
            title = "Дюна",
            author = "Фрэнк Герберт",
            filePath = "dune.epub"
        ),
        Book(
            title = "Задача трёх тел",
            author = "Лю Цысинь",
            filePath = "three_body_problem.epub"
        ),
        Book(
            title = "Очень длинное название книги, которое не должно помещаться в одну строку и должно красиво переноситься",
            author = "Автор с Очень Длинным Именем и Фамилией",
            filePath = "long_title_book.epub"
        )
    )
}