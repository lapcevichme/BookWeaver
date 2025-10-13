package com.lapcevichme.bookweaverdesktop.data.backend

import io.ktor.client.statement.HttpResponse
import java.io.File

/**
 * Управляет операциями, связанными с проектами (книгами),
 * такими как получение списка проектов и импорт новых книг через API.
 */
class BookManager(private val apiClient: ApiClient) {

    /**
     * Запрашивает список доступных проектов с бэкенд API.
     */
    suspend fun getProjectList(): Result<List<String>> {
        return apiClient.getProjects()
    }

    /**
     * Импортирует выбранный файл книги, отправляя его на бэкенд.
     * @param bookFile Файл для импорта.
     * @return Result, содержащий HttpResponse от сервера.
     */
    suspend fun importBook(bookFile: File): Result<HttpResponse> {
        return apiClient.importBook(bookFile)
    }

    /*
     * УДАЛЕН МЕТОД `selectBookFile()`
     *
     * ПРИЧИНА: Этот метод использовал JFileChooser (компонент Swing UI) внутри
     * класса бизнес-логики. Это является плохой практикой, так как "ядро"
     * приложения не должно зависеть от конкретной реализации UI.
     *
     * ЧТО ДЕЛАТЬ ВМЕСТО ЭТОГО:
     * 1. В вашем UI-коде (например, в Composable-функции экрана) используйте
     * системный диалог выбора файла, подходящий для вашей платформы
     * (например, `FileDialog` в Compose for Desktop).
     * 2. После того как пользователь выберет файл, вы получите объект `File`.
     * 3. Этот объект `File` передайте во ViewModel, которая затем вызовет
     * метод `importBook(file)` из этого менеджера.
     *
     * Таким образом, UI отвечает за UI, а бизнес-логика - за работу с данными и API.
     */
}
