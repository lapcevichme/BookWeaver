package com.lapcevichme.bookweaver.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lapcevichme.bookweaver.core.SubtitleEntry
import com.lapcevichme.bookweaver.core.WordEntry
import com.lapcevichme.bookweaver.data.dataStore
import com.lapcevichme.bookweaver.data.database.BookDao
import com.lapcevichme.bookweaver.data.database.entities.BookEntity
import com.lapcevichme.bookweaver.data.database.entities.ChapterEntity
import com.lapcevichme.bookweaver.data.database.toDomain
import com.lapcevichme.bookweaver.data.database.toEntity
import com.lapcevichme.bookweaver.data.network.dto.BookManifestDto
import com.lapcevichme.bookweaver.data.network.dto.ChapterSummaryDto
import com.lapcevichme.bookweaver.data.network.dto.CharacterDto
import com.lapcevichme.bookweaver.data.network.dto.ScenarioEntryDto
import com.lapcevichme.bookweaver.data.network.mapper.toDomain
import com.lapcevichme.bookweaver.domain.model.Book
import com.lapcevichme.bookweaver.domain.model.BookCharacter
import com.lapcevichme.bookweaver.domain.model.BookDetails
import com.lapcevichme.bookweaver.domain.model.BookSource
import com.lapcevichme.bookweaver.domain.model.Chapter
import com.lapcevichme.bookweaver.domain.model.ChapterMedia
import com.lapcevichme.bookweaver.domain.model.ChapterSummary
import com.lapcevichme.bookweaver.domain.model.DomainWordEntry
import com.lapcevichme.bookweaver.domain.model.DownloadProgress
import com.lapcevichme.bookweaver.domain.model.DownloadState
import com.lapcevichme.bookweaver.domain.model.PlaybackEntry
import com.lapcevichme.bookweaver.domain.model.PlayerChapterInfo
import com.lapcevichme.bookweaver.domain.model.ScenarioEntry
import com.lapcevichme.bookweaver.domain.repository.BookRepository
import com.lapcevichme.bookweaver.domain.repository.ServerRepository
import com.materialkolor.ktx.themeColor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val bookDao: BookDao,
    private val remoteDataSource: RemoteDataSource,
    private val serverRepository: ServerRepository
) : BookRepository {

    private object PreferencesKeys {
        val ACTIVE_BOOK_ID = stringPreferencesKey("active_book_id")
        val ACTIVE_CHAPTER_ID = stringPreferencesKey("active_chapter_id")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val booksDir = File(context.filesDir, "books")

    init {
        if (!booksDir.exists()) {
            booksDir.mkdirs()
        }
    }

    override fun getBooks(): Flow<List<Book>> = bookDao.getAllBooks()
        .map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getBookDetails(bookId: String): Result<BookDetails> =
        withContext(Dispatchers.IO) {
            val bookEntity = bookDao.getBookById(bookId)
                ?: return@withContext Result.failure(Exception("Книга $bookId не найдена в БД"))

            when (bookEntity.source) {
                BookSource.LOCAL_ONLY -> getBookDetailsFromLocal(bookEntity)
                BookSource.SERVER -> getBookDetailsFromServer(bookEntity)
            }
        }

    private suspend fun getBookDetailsFromServer(book: BookEntity): Result<BookDetails> {
        return try {
            val structure = remoteDataSource.fetchBookStructure(book.id).getOrThrow()
            val localChaptersMap = bookDao.getChaptersForBook(book.id).first()
                .associateBy { it.id }

            val entitiesToInsert = mutableListOf<ChapterEntity>()
            val domainChapters = mutableListOf<Chapter>()

            structure.chapters.forEachIndexed { index, chapterStruct ->
                val localChapter = localChaptersMap[chapterStruct.id]

                // Приоритет: если скачано локально - верим файловой системе, иначе верим серверу
                val finalHasAudio = if (localChapter?.downloadState == DownloadState.DOWNLOADED) {
                    localChapter.hasAudio // Берем сохраненное значение (которое мы обновим при скачивании)
                } else {
                    chapterStruct.hasAudio
                }

                val entity = ChapterEntity(
                    id = chapterStruct.id,
                    bookId = book.id,
                    title = chapterStruct.title,
                    chapterIndex = index,
                    downloadState = localChapter?.downloadState ?: DownloadState.NOT_DOWNLOADED,
                    localAudioPath = localChapter?.localAudioPath,
                    localScenarioPath = localChapter?.localScenarioPath,
                    localSubtitlesPath = localChapter?.localSubtitlesPath,
                    localOriginalTextPath = localChapter?.localOriginalTextPath,
                    remoteVersion = chapterStruct.version,
                    hasAudio = finalHasAudio
                )
                entitiesToInsert.add(entity)

                val domainChapter = Chapter(
                    id = chapterStruct.id,
                    title = chapterStruct.title,
                    downloadState = localChapter?.downloadState ?: DownloadState.NOT_DOWNLOADED,
                    audioDirectoryPath = localChapter?.localAudioPath,
                    scenarioPath = localChapter?.localScenarioPath,
                    subtitlesPath = localChapter?.localSubtitlesPath,
                    volumeNumber = chapterStruct.volumeNumber ?: 1,
                    hasAudio = finalHasAudio
                )
                domainChapters.add(domainChapter)
            }

            bookDao.upsertChapters(entitiesToInsert)

            val manifest = structure.manifest.toDomain()

            Result.success(
                BookDetails(
                    manifest = manifest,
                    bookCharacters = emptyList(),
                    summaries = emptyMap(),
                    chapters = domainChapters
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override suspend fun getCharacters(bookId: String): Result<List<BookCharacter>> =
        withContext(Dispatchers.IO) {
            val bookEntity = bookDao.getBookById(bookId)
            if (bookEntity?.source == BookSource.LOCAL_ONLY) {
                return@withContext getBookDetailsFromLocal(bookEntity).map { it.bookCharacters }
            }

            try {
                val dtos = remoteDataSource.fetchBookCharacters(bookId).getOrThrow()
                val domain = dtos.map { dto ->
                    BookCharacter(
                        id = try {
                            UUID.fromString(dto.id)
                        } catch (e: Exception) {
                            UUID.randomUUID()
                        },
                        name = dto.name,
                        description = dto.shortRole ?: "",
                        spoilerFreeDescription = "",
                        aliases = emptyList(),
                        chapterMentions = emptyMap()
                    )
                }
                Result.success(domain)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun getCharacterDetails(
        bookId: String,
        characterId: String
    ): Result<BookCharacter> = withContext(Dispatchers.IO) {
        val bookEntity = bookDao.getBookById(bookId)
        if (bookEntity?.source == BookSource.LOCAL_ONLY) {
            val chars =
                getBookDetailsFromLocal(bookEntity).getOrNull()?.bookCharacters ?: emptyList()
            val char = chars.find { it.id.toString() == characterId }
            return@withContext if (char != null) Result.success(char) else Result.failure(
                Exception(
                    "Not found"
                )
            )
        }

        try {
            val dto = remoteDataSource.fetchCharacterDetails(bookId, characterId).getOrThrow()
            Result.success(dto.toDomain())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChapterSummary(
        bookId: String,
        chapterId: String
    ): Result<ChapterSummary> = withContext(Dispatchers.IO) {
        val bookEntity = bookDao.getBookById(bookId)
        if (bookEntity?.source == BookSource.LOCAL_ONLY) {
            val summaries = getBookDetailsFromLocal(bookEntity).getOrNull()?.summaries ?: emptyMap()
            val summary = summaries[chapterId]
            return@withContext if (summary != null) Result.success(summary) else Result.failure(
                Exception("Not found")
            )
        }

        try {
            val dto = remoteDataSource.fetchChapterInfo(bookId, chapterId).getOrThrow()
            Result.success(
                ChapterSummary(
                    chapterId = dto.chapterId,
                    teaser = dto.teaser ?: "",
                    synopsis = dto.synopsis ?: ""
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getBookDetailsFromLocal(book: BookEntity): Result<BookDetails> {
        try {
            val bookDir = File(book.localPath!!)
            if (!bookDir.exists()) throw Exception("Книга с id ${book.id} не найдена")

            val manifestFile = File(bookDir, "manifest.json")
            val manifest =
                json.decodeFromString<BookManifestDto>(manifestFile.readText()).toDomain()

            val charactersFile = File(bookDir, "character_archive.json")
            val characters = if (charactersFile.exists()) {
                json.decodeFromString<List<CharacterDto>>(charactersFile.readText())
                    .map { it.toDomain() }
            } else emptyList()

            val summariesFile = File(bookDir, "chapter_summaries.json")
            val summaries = if (summariesFile.exists()) {
                json.decodeFromString<Map<String, ChapterSummaryDto>>(summariesFile.readText())
                    .mapValues { it.value.toDomain() }
            } else emptyMap()

            val chapterDirs = bookDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("vol_") }
                ?.sortedWith(
                    compareBy(
                        { file ->
                            file.name.substringAfter("vol_").substringBefore("_chap")
                                .toIntOrNull() ?: Int.MAX_VALUE
                        },
                        { file ->
                            file.name.substringAfter("chap_").toIntOrNull() ?: Int.MAX_VALUE
                        }
                    )) ?: emptyList()

            val chapterEntities = chapterDirs.mapIndexed { index, chapterDir ->
                val originalTextFile = File(chapterDir, "original_text.txt")
                val hasAudio = File(chapterDir, "audio").exists()

                ChapterEntity(
                    id = chapterDir.name,
                    bookId = book.id,
                    title = formatChapterIdToTitle(chapterDir.name),
                    downloadState = DownloadState.DOWNLOADED,
                    localAudioPath = File(chapterDir, "audio").absolutePath,
                    localScenarioPath = File(chapterDir, "scenario.json").absolutePath,
                    localSubtitlesPath = File(
                        chapterDir,
                        "subtitles.json"
                    ).takeIf { it.exists() }?.absolutePath,
                    localOriginalTextPath = originalTextFile.absolutePath.takeIf { originalTextFile.exists() },
                    chapterIndex = index,
                    remoteVersion = 1,
                    hasAudio = hasAudio
                )
            }
            bookDao.upsertChapters(chapterEntities)

            val chapters = chapterEntities.map { entity ->
                Chapter(
                    id = entity.id,
                    title = entity.title,
                    downloadState = entity.downloadState,
                    audioDirectoryPath = entity.localAudioPath,
                    scenarioPath = entity.localScenarioPath,
                    subtitlesPath = entity.localSubtitlesPath,
                    volumeNumber = entity.id.substringBefore("_chap").substringAfter("vol_")
                        .toIntOrNull() ?: 1,
                    hasAudio = entity.hasAudio
                )
            }

            return Result.success(BookDetails(manifest, characters, summaries, chapters))
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure(e)
        }
    }

    private fun formatChapterIdToTitle(id: String): String {
        return try {
            val parts = id.split("_")
            val volume = parts[1]
            val chapter = parts[3]
            "Том $volume, Глава $chapter"
        } catch (e: Exception) {
            id.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
    }

    override suspend fun getScenarioForChapter(
        bookId: String,
        chapterId: String
    ): Result<List<ScenarioEntry>> = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(booksDir, bookId)
            val scenarioFile = File(bookDir, "$chapterId/scenario.json")
            if (!scenarioFile.exists()) throw Exception("Scenario file not found at ${scenarioFile.path}")

            val scenario = json.decodeFromString<List<ScenarioEntryDto>>(scenarioFile.readText())
                .map { it.toDomain() }
            Result.success(scenario)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override suspend fun getPlaybackData(
        bookId: String,
        chapterId: String
    ): Result<Pair<List<PlaybackEntry>, String>> = withContext(Dispatchers.IO) {

        val chapterEntity = bookDao.getChapter(chapterId)
        val bookEntity = bookDao.getBookById(bookId)
            ?: return@withContext Result.failure(Exception("Книга $bookId не найдена в БД"))

        val useLocal = chapterEntity?.downloadState == DownloadState.DOWNLOADED &&
                chapterEntity.localAudioPath != null

        if (useLocal) {
            return@withContext getPlaybackDataFromLocal(
                bookId,
                chapterId,
                chapterEntity.localAudioPath!!
            )
        }

        try {
            val serverHost = bookEntity.serverHost
                ?: return@withContext Result.failure(Exception("Книга не привязана к серверу"))

            val (entries, audioUrl) = remoteDataSource.fetchPlaybackData(bookId, chapterId)
                .getOrThrow()

            val fullAudioUrl = if (audioUrl.startsWith("http")) {
                audioUrl
            } else {
                val host = serverHost.removeSuffix("/")
                val path = audioUrl.removePrefix("/")
                "$host/$path"
            }

            Result.success(Pair(entries, fullAudioUrl))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getPlaybackDataFromLocal(
        bookId: String,
        chapterId: String,
        audioDirectoryPath: String
    ): Result<Pair<List<PlaybackEntry>, String>> {
        try {
            val bookDir = File(booksDir, bookId)
            val chapterDir = File(bookDir, chapterId)
            if (!chapterDir.exists()) throw Exception("Chapter directory not found")

            val scenarioFile = File(chapterDir, "scenario.json")
            if (!scenarioFile.exists()) throw Exception("scenario.json not found")
            val scenarioDtoList =
                json.decodeFromString<List<ScenarioEntryDto>>(scenarioFile.readText())

            val scenarioMap = scenarioDtoList.associateBy { it.id }

            val subtitlesFile = File(chapterDir, "subtitles.json")
            if (!subtitlesFile.exists()) throw Exception("subtitles.json not found")

            val subtitleEntryList =
                json.decodeFromString<List<SubtitleEntry>>(subtitlesFile.readText())

            if (!File(audioDirectoryPath).exists()) throw Exception("audio directory not found")

            val mergedList = subtitleEntryList.map { subtitleEntry ->
                val key = subtitleEntry.audioFile.removeSuffix(".wav")
                val scenarioDto = scenarioMap[key]

                val text = scenarioDto?.text ?: subtitleEntry.text
                val speaker = scenarioDto?.speaker ?: "Рассказчик"
                val ambient = scenarioDto?.ambient ?: "none"
                val emotion = scenarioDto?.emotion
                val type = scenarioDto?.type ?: "narration"

                PlaybackEntry(
                    id = key,
                    audioFile = subtitleEntry.audioFile,
                    text = text,
                    startMs = subtitleEntry.startMs,
                    endMs = subtitleEntry.endMs,
                    words = subtitleEntry.words.map { it.toDomain() },
                    speaker = speaker,
                    ambient = ambient,
                    emotion = emotion,
                    type = type
                )
            }

            return Result.success(Pair(mergedList, audioDirectoryPath))

        } catch (e: Exception) {
            return Result.failure(e)
        }
    }


    override fun downloadAndInstallBook(url: String): Flow<DownloadProgress> = flow {
        var tempFile: File? = null
        try {
            emit(DownloadProgress.Downloading(0L, 0L))

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) throw Exception("Ошибка скачивания: ${response.code}")

            val body = response.body
            val totalBytes = body.contentLength()
            tempFile = File.createTempFile("install_", ".bw", context.cacheDir)

            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        emit(DownloadProgress.Downloading(totalBytesRead, totalBytes))
                    }
                }
            }

            emit(DownloadProgress.Installing)

            val installResult = installBookFromFile(tempFile, BookSource.LOCAL_ONLY, null)
            if (installResult.isFailure) {
                throw installResult.exceptionOrNull() ?: Exception("Неизвестная ошибка установки")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        } finally {
            tempFile?.delete()
        }
    }.flowOn(Dispatchers.IO)


    override suspend fun installBook(inputStream: InputStream): Result<File> =
        withContext(Dispatchers.IO) {
            val tempFile = File.createTempFile("install_", ".bw", context.cacheDir)
            try {
                FileOutputStream(tempFile).use { output ->
                    inputStream.use { input -> input.copyTo(output) }
                }
                installBookFromFile(tempFile, BookSource.LOCAL_ONLY, null)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                tempFile.delete()
            }
        }

    private suspend fun installBookFromFile(
        file: File,
        source: BookSource,
        serverHost: String?
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            var bookId: String?
            lateinit var manifest: BookManifestDto

            ZipFile(file).use { zipFile ->
                val manifestEntry = zipFile.getEntry("manifest.json")
                    ?: return@withContext Result.failure(Exception("manifest.json не найден в архиве"))

                val manifestContent =
                    zipFile.getInputStream(manifestEntry).bufferedReader().readText()
                manifest = json.decodeFromString<BookManifestDto>(manifestContent)
                bookId = manifest.bookName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").lowercase()
            }

            if (bookId == null) {
                return@withContext Result.failure(Exception("Не удалось определить ID книги из манифеста"))
            }

            val bookDir = File(booksDir, bookId)
            if (bookDir.exists()) bookDir.deleteRecursively()
            bookDir.mkdirs()

            ZipFile(file).use { zipFile ->
                for (entry in zipFile.entries()) {
                    val outputFile = File(bookDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outputFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            val coverFile = File(bookDir, "cover.jpg")

            val domainBook = Book(
                id = bookId,
                title = manifest.bookName,
                author = manifest.author,
                localPath = bookDir.absolutePath,
                coverPath = if (coverFile.exists()) coverFile.absolutePath else null,
                source = source
            )

            val entity = domainBook.toEntity(null).copy(
                serverHost = serverHost
            )
            bookDao.upsertBook(entity)

            return@withContext Result.success(bookDir)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.failure(e)
        }
    }


    override suspend fun getChapterOriginalText(bookId: String, chapterId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val bookEntity = bookDao.getBookById(bookId)
                    ?: throw Exception("Книга $bookId не найдена в БД")
                val chapterEntity = bookDao.getChapter(chapterId)

                val useLocal = (bookEntity.source == BookSource.LOCAL_ONLY) ||
                        (chapterEntity?.downloadState == DownloadState.DOWNLOADED && chapterEntity.localOriginalTextPath != null)

                if (useLocal) {
                    val path = chapterEntity?.localOriginalTextPath
                        ?: parseChapterIdToLocalTextPath(bookId, chapterId)

                    val textFile = File(path)
                    if (!textFile.exists()) throw Exception("Original text file not found at ${textFile.path}")
                    return@withContext Result.success(textFile.readText())
                }

                if (bookEntity.source == BookSource.SERVER) {
                    return@withContext remoteDataSource.fetchOriginalText(bookId, chapterId)
                }

                throw Exception("Не удалось загрузить оригинальный текст")
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure(e)
            }
        }


    private fun parseChapterIdToLocalTextPath(bookId: String, chapterId: String): String {
        val (volNum, chapNum) = parseChapterId(chapterId)
        return File(
            booksDir,
            "$bookId/book_source/$bookId/vol_$volNum/chapter_$chapNum.txt"
        ).absolutePath
    }

    private fun parseChapterId(chapterId: String): Pair<String, String> {
        return try {
            val parts = chapterId.split("_")
            val volNum = parts[1]
            val chapNum = parts[3]
            volNum to chapNum
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid chapterId format: $chapterId")
        }
    }

    override suspend fun deleteBook(bookId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val bookDir = File(booksDir, bookId)
            if (bookDir.exists()) {
                val deleted = bookDir.deleteRecursively()
                if (!deleted) {
                    throw Exception("Failed to delete book directory.")
                }
            }
            bookDao.deleteBook(bookId)

            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override fun getActiveBookIdFlow(): Flow<String?> {
        return context.dataStore.data
            .map { preferences ->
                preferences[PreferencesKeys.ACTIVE_BOOK_ID]
            }
    }

    override suspend fun setActiveBookId(bookId: String?) {
        context.dataStore.edit { settings ->
            if (bookId == null) {
                settings.remove(PreferencesKeys.ACTIVE_BOOK_ID)
            } else {
                settings[PreferencesKeys.ACTIVE_BOOK_ID] = bookId
            }
        }
    }

    override suspend fun getActiveBookId(): String? {
        return context.dataStore.data.first()[PreferencesKeys.ACTIVE_BOOK_ID]
    }

    override suspend fun setActiveChapterId(chapterId: String?) {
        context.dataStore.edit { settings ->
            if (chapterId == null) {
                settings.remove(PreferencesKeys.ACTIVE_CHAPTER_ID)
            } else {
                settings[PreferencesKeys.ACTIVE_CHAPTER_ID] = chapterId
            }
        }
    }

    override fun getActiveChapterIdFlow(): Flow<String?> {
        return context.dataStore.data
            .map { preferences ->
                preferences[PreferencesKeys.ACTIVE_CHAPTER_ID]
            }
    }

    override suspend fun getActiveChapterId(): String? {
        return context.dataStore.data.first()[PreferencesKeys.ACTIVE_CHAPTER_ID]
    }

    override suspend fun getPlayerChapterInfo(
        bookId: String,
        chapterId: String
    ): Result<PlayerChapterInfo> = withContext(Dispatchers.IO) {
        try {
            val bookEntity = bookDao.getBookById(bookId)
                ?: throw Exception("Книга $bookId не найдена в базе данных")

            val chapterEntity = bookDao.getChapter(chapterId)

            if (chapterEntity == null && bookEntity.source == BookSource.LOCAL_ONLY) {
                return@withContext getPlayerChapterInfoFromLocal(bookEntity, chapterId)
            }

            if (chapterEntity == null) {
                throw Exception("Глава $chapterId не найдена в БД")
            }

            val media: ChapterMedia
            if (chapterEntity.downloadState == DownloadState.DOWNLOADED) {
                media = ChapterMedia(
                    subtitlesPath = chapterEntity.localSubtitlesPath,
                    audioDirectoryPath = chapterEntity.localAudioPath
                        ?: throw Exception("БД не консистентна: глава ${chapterId} СКАЧАНА, но localAudioPath is null")
                )
            } else {
                media = ChapterMedia(
                    subtitlesPath = "REMOTE_PLACEHOLDER",
                    audioDirectoryPath = "REMOTE_PLACEHOLDER"
                )
            }

            val lastPosition =
                if (bookEntity.lastListenedChapterId == chapterId) bookEntity.lastListenedPosition else 0L

            val info = PlayerChapterInfo(
                bookTitle = bookEntity.title,
                chapterTitle = chapterEntity.title,
                coverPath = bookEntity.coverPath,
                media = media,
                lastListenedPosition = lastPosition
            )
            Result.success(info)

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private suspend fun getPlayerChapterInfoFromLocal(
        bookEntity: BookEntity,
        chapterId: String
    ): Result<PlayerChapterInfo> {
        try {
            val bookDetails = getBookDetailsFromLocal(bookEntity).getOrThrow()
            val chapter = bookDetails.chapters.firstOrNull { it.id == chapterId }
                ?: throw Exception("Глава $chapterId не найдена в книге ${bookEntity.id}")

            if (chapter.subtitlesPath == null || !File(chapter.subtitlesPath).exists()) {
                throw Exception("Файл субтитров (subtitles.json) не найден для главы $chapterId")
            }
            if (chapter.audioDirectoryPath == null || !File(chapter.audioDirectoryPath).exists()) {
                throw Exception("Папка 'audio' не найдена для главы $chapterId")
            }

            val coverPath = bookEntity.coverPath
            val lastPosition =
                if (bookEntity.lastListenedChapterId == chapterId) bookEntity.lastListenedPosition else 0L

            val media = ChapterMedia(
                subtitlesPath = chapter.subtitlesPath,
                audioDirectoryPath = chapter.audioDirectoryPath!!
            )

            val info = PlayerChapterInfo(
                bookTitle = bookDetails.manifest.bookName,
                chapterTitle = chapter.title,
                coverPath = coverPath,
                media = media,
                lastListenedPosition = lastPosition
            )
            return Result.success(info)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private val fallbackColor = Color(0xFF00668B)

    override fun getBookThemeColorFlow(bookId: String): Flow<Int?> {
        return bookDao.getBookThemeColor(bookId)
    }

    override suspend fun generateAndCacheThemeColor(bookId: String, coverPath: String?) {
        withContext(Dispatchers.IO) {
            try {
                val currentColor = bookDao.getBookThemeColor(bookId).first()
                if (currentColor != null) return@withContext

                if (coverPath == null) return@withContext

                val bitmap = if (coverPath.startsWith("http")) {
                    val connection = serverRepository.getCurrentConnection()
                    val requestBuilder = Request.Builder().url(coverPath)

                    if (connection != null) {
                        requestBuilder.header("Authorization", "Bearer ${connection.token}")
                    }

                    val response = httpClient.newCall(requestBuilder.build()).execute()
                    if (response.isSuccessful) {
                        response.body.byteStream().use { BitmapFactory.decodeStream(it) }
                    } else {
                        null
                    }
                } else {
                    val coverFile = File(coverPath)
                    if (coverFile.exists()) {
                        BitmapFactory.decodeFile(coverFile.absolutePath)
                    } else {
                        null
                    }
                }

                if (bitmap == null) return@withContext

                val seedColor = bitmap.asImageBitmap().themeColor(fallback = fallbackColor)
                val newColorInt = seedColor.toArgb()

                bookDao.updateThemeColor(bookId, newColorInt)
                bitmap.recycle()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun saveListenProgress(bookId: String, chapterId: String, position: Long) {
        withContext(Dispatchers.IO) {
            bookDao.updateListenProgress(bookId, chapterId, position)
        }
    }

    override suspend fun getAmbientTrackPath(
        bookId: String,
        ambientName: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            if (ambientName.isEmpty() || ambientName.equals("none", ignoreCase = true)) {
                return@withContext Result.success(null)
            }

            val bookEntity = bookDao.getBookById(bookId)
                ?: throw Exception("Книга $bookId не найдена")

            if (bookEntity.source == BookSource.SERVER) {
                val serverHost = bookEntity.serverHost
                val host = serverHost?.removeSuffix("/")
                val ambientUrl = "$host/static/ambient/$ambientName.mp3"
                return@withContext Result.success(ambientUrl)
            }

            val ambientDir = File(booksDir, "$bookId/ambient")
            val ambientFileAsIs = File(ambientDir, ambientName)
            if (ambientFileAsIs.exists() && ambientFileAsIs.isFile) {
                return@withContext Result.success(ambientFileAsIs.absolutePath)
            }

            val possibleExtensions = listOf(".mp3", ".ogg", ".wav")
            for (ext in possibleExtensions) {
                val ambientFileWithExt = File(ambientDir, "$ambientName$ext")
                if (ambientFileWithExt.exists() && ambientFileWithExt.isFile) {
                    return@withContext Result.success(ambientFileWithExt.absolutePath)
                }
            }

            Log.w("BookRepositoryImpl", "Ambient file not found (local): $ambientName")
            Result.success(null)

        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun WordEntry.toDomain(): DomainWordEntry {
        return DomainWordEntry(
            word = this.word,
            start = this.start,
            end = this.end
        )
    }

    override suspend fun getLastListenedChapterId(bookId: String): String? =
        withContext(Dispatchers.IO) {
            return@withContext bookDao.getBookById(bookId)?.lastListenedChapterId
        }


    override suspend fun syncLibraryWithRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connection = serverRepository.getCurrentConnection()
                ?: return@withContext Result.failure(Exception("Сервер не подключен"))

            val remoteBooksDto = remoteDataSource.fetchBookList().getOrThrow()
            val localBooksMap = bookDao.getAllBooks().first().associateBy { it.id }

            for (remoteBookDto in remoteBooksDto) {
                val remoteBookId =
                    remoteBookDto.bookName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").lowercase()
                val localBook = localBooksMap[remoteBookId]

                val host = connection.host.removeSuffix("/")
                val remoteCoverUrl = "$host/static/books/${remoteBookId}/cover.jpg"

                if (localBook == null) {
                    val newEntity = BookEntity(
                        id = remoteBookId,
                        title = remoteBookDto.bookName,
                        author = remoteBookDto.author,
                        source = BookSource.SERVER,
                        serverHost = connection.host,
                        remoteCoverUrl = remoteCoverUrl,
                        remoteManifestVersion = remoteBookDto.version,
                        localPath = null,
                        coverPath = remoteCoverUrl,
                        themeColor = null,
                        lastListenedChapterId = null,
                        lastListenedPosition = 0L
                    )
                    bookDao.upsertBook(newEntity)
                } else if (localBook.source == BookSource.LOCAL_ONLY) {
                    bookDao.updateBookSource(
                        bookId = localBook.id,
                        serverHost = connection.host,
                        remoteCoverUrl = remoteCoverUrl,
                        remoteVersion = remoteBookDto.version
                    )
                } else {
                    val updatedEntity = localBook.copy(
                        title = remoteBookDto.bookName,
                        author = remoteBookDto.author,
                        remoteManifestVersion = remoteBookDto.version,
                        coverPath = remoteCoverUrl,
                        serverHost = connection.host
                    )
                    bookDao.upsertBook(updatedEntity)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override fun downloadChapter(bookId: String, chapterId: String): Flow<DownloadProgress> = flow {
        var tempFile: File? = null
        try {
            emit(DownloadProgress.Downloading(0L, 0L))
            bookDao.updateChapterDownloadState(chapterId, DownloadState.DOWNLOADING)

            val response = remoteDataSource.downloadChapterZip(bookId, chapterId).getOrThrow()
            val body = response.body() ?: throw Exception("Empty response body")
            val totalBytes = body.contentLength()
            tempFile = File.createTempFile("chapter_", ".zip", context.cacheDir)

            FileOutputStream(tempFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        emit(DownloadProgress.Downloading(totalBytesRead, totalBytes))
                    }
                }
            }
            emit(DownloadProgress.Installing)

            val chapterDir = File(booksDir, "$bookId/$chapterId")
            if (chapterDir.exists()) chapterDir.deleteRecursively()
            chapterDir.mkdirs()

            ZipFile(tempFile).use { zipFile ->
                for (entry in zipFile.entries()) {
                    val outputFile = File(chapterDir, entry.name)
                    if (entry.isDirectory) {
                        outputFile.mkdirs()
                    } else {
                        outputFile.parentFile?.mkdirs()
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outputFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }

            val audioDir = File(chapterDir, "audio")
            val hasLocalAudio = audioDir.exists() && (audioDir.listFiles()?.isNotEmpty() == true)

            val originalTextFile = File(chapterDir, "original_text.txt")
            val hasLocalText = originalTextFile.exists()

            val localAudioPath = if (hasLocalAudio) audioDir.absolutePath else null
            val localOriginalTextPath = if (hasLocalText) originalTextFile.absolutePath else null

            bookDao.updateChapterDownloadPaths(
                chapterId = chapterId,
                state = DownloadState.DOWNLOADED,
                localAudioPath = localAudioPath,
                localScenarioPath = File(chapterDir, "scenario.json").absolutePath,
                localSubtitlesPath = File(chapterDir, "subtitles.json").absolutePath,
                localOriginalTextPath = localOriginalTextPath,
                hasAudio = hasLocalAudio
            )

        } catch (e: Exception) {
            e.printStackTrace()
            bookDao.updateChapterDownloadState(chapterId, DownloadState.ERROR)
            throw e
        } finally {
            tempFile?.delete()
        }
    }.flowOn(Dispatchers.IO)
}