package com.lapcevichme.bookweaverdesktop.backend

import com.lapcevichme.bookweaverdesktop.settings.AppSettings
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Manages project-related operations like fetching the project list and importing new books.
 */
class BookManager(
    private val apiClient: ApiClient,
    private val settings: AppSettings
) {

    /**
     * Fetches the list of available projects from the backend API.
     */
    suspend fun getProjectList(): Result<List<String>> {
        return apiClient.getProjects()
    }

    /**
     * Opens a system file chooser dialog to select a book file to import.
     * @return The selected File, or null if the dialog was cancelled.
     */
    suspend fun selectBookFile(): File? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Выберите файл книги (.txt, .epub)"
            fileFilter = FileNameExtensionFilter("Book Files (.txt, .epub)", "txt", "epub")
            isAcceptAllFileFilterUsed = false
            // You can set the last used directory here from settings for better UX
            // currentDirectory = File(settings.lastBookDirectory)
        }
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            // Save the directory for next time
            // settings.lastBookDirectory = chooser.currentDirectory.absolutePath
            chooser.selectedFile
        } else {
            null
        }
    }

    /**
     * Imports a selected book file by sending it to the backend.
     * @param bookFile The file to import.
     * @return A Result containing the server's HttpResponse.
     */
    suspend fun importBook(bookFile: File): Result<HttpResponse> {
        return apiClient.importBook(bookFile)
    }
}
