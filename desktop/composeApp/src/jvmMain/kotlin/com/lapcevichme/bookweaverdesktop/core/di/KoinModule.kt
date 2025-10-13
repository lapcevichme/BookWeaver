package com.lapcevichme.bookweaverdesktop.core.di

import com.lapcevichme.bookweaverdesktop.data.backend.ApiClient
import com.lapcevichme.bookweaverdesktop.data.backend.BackendProcessManager
import com.lapcevichme.bookweaverdesktop.data.repository.BackendRepositoryImpl
import com.lapcevichme.bookweaverdesktop.data.repository.ConfigRepositoryImpl
import com.lapcevichme.bookweaverdesktop.data.repository.ProjectRepositoryImpl
import com.lapcevichme.bookweaverdesktop.data.repository.TaskRepositoryImpl
import com.lapcevichme.bookweaverdesktop.domain.repository.BackendRepository
import com.lapcevichme.bookweaverdesktop.domain.repository.ConfigRepository
import com.lapcevichme.bookweaverdesktop.domain.repository.ProjectRepository
import com.lapcevichme.bookweaverdesktop.domain.repository.TaskRepository
import com.lapcevichme.bookweaverdesktop.domain.usecase.*
import com.lapcevichme.bookweaverdesktop.server.ServerManager
import com.lapcevichme.bookweaverdesktop.core.settings.SettingsManager
import com.lapcevichme.bookweaverdesktop.ui.MainViewModel
import com.lapcevichme.bookweaverdesktop.ui.dashboard.DashboardViewModel
import com.lapcevichme.bookweaverdesktop.ui.editor.manifest.ManifestEditorViewModel
import com.lapcevichme.bookweaverdesktop.ui.editor.scenario.ScenarioEditorViewModel
import com.lapcevichme.bookweaverdesktop.ui.settings.SettingsAndAssetsViewModel
import com.lapcevichme.bookweaverdesktop.ui.workspace.WorkspaceViewModel
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val appModule = module {

    // --- Core & Data Layer ---
    single {
        Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
    }
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(get()) }
        }
    }
    singleOf(::ApiClient)
    singleOf(::SettingsManager)
    single { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    singleOf(::BackendProcessManager)
    singleOf(::ServerManager)

    // --- Repository Layer ---
    single<ProjectRepository> { ProjectRepositoryImpl(get(), get()) }
    single<TaskRepository> { TaskRepositoryImpl(get()) }
    single<ConfigRepository> { ConfigRepositoryImpl(get()) }
    single<BackendRepository> { BackendRepositoryImpl(get(), get()) }


    // --- Domain Layer (Use Cases) ---
    factoryOf(::GetProjectsProgressUseCase)
    factoryOf(::ImportBookUseCase)
    factoryOf(::GetProjectDetailsUseCase)
    factoryOf(::GetBookArtifactUseCase)
    factoryOf(::UpdateBookArtifactUseCase)
    factoryOf(::GetChapterScenarioUseCase)
    factoryOf(::UpdateChapterScenarioUseCase)
    factoryOf(::StartScenarioGenerationUseCase)
    factoryOf(::StartTtsSynthesisUseCase)
    factoryOf(::GetTaskStatusUseCase)
    factoryOf(::ObserveBackendStateUseCase)
    factoryOf(::ObserveBackendLogsUseCase)
    factoryOf(::StartBackendUseCase)
    factoryOf(::StopBackendUseCase)
    factoryOf(::GetConfigContentUseCase)
    factoryOf(::SaveConfigContentUseCase)
    factoryOf(::StartCharacterAnalysisUseCase)
    factoryOf(::StartSummaryGenerationUseCase)
    factoryOf(::StartVoiceConversionUseCase)


    // --- UI Layer (View Models) ---
    factory { MainViewModel(get(), get()) }
    factory { DashboardViewModel(get(), get()) }
    factory { (bookName: String) ->
        WorkspaceViewModel(
            bookName = bookName,
            getProjectDetailsUseCase = get(),
            startScenarioGenerationUseCase = get(),
            startTtsSynthesisUseCase = get(),
            startCharacterAnalysisUseCase = get(),
            startSummaryGenerationUseCase = get(),
            startVoiceConversionUseCase = get(),
            getTaskStatusUseCase = get()
        )
    }
    factory { (bookName: String, volume: Int, chapter: Int) ->
        ScenarioEditorViewModel(bookName, volume, chapter, get(), get())
    }
    factory { (bookName: String) ->
        ManifestEditorViewModel(bookName, get(), get(), get())
    }
    factory { SettingsAndAssetsViewModel(get(), get(), get()) }
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) =
    startKoin {
        appDeclaration()
        modules(appModule)
    }
