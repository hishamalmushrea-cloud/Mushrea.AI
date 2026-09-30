package com.mushrea.code.di

import android.os.Build
import com.mushrea.code.MushreaCodeApplication
import com.mushrea.code.core.api.GitHubApiClient
import com.mushrea.code.core.notification.RuntimeNotificationHelper
import com.mushrea.code.data.connection.SecureSettingsRepository
import com.mushrea.code.data.repository.AndroidRuntimeActivityMessages
import com.mushrea.code.data.repository.AndroidRuntimeCatalogMessages
import com.mushrea.code.data.repository.PullRequestStatusRepository
import com.mushrea.code.data.settings.AppPreferencesRepository
import com.mushrea.code.data.settings.DraftRepository
import com.mushrea.code.device.DeviceAgentStore
import com.mushrea.code.feature.wakeword.VoskModelStore
import com.mushrea.code.runtime.RuntimeActivityRepository
import com.mushrea.code.runtime.RuntimeCatalogRepository
import com.mushrea.code.runtime.RuntimeRegistry
import com.mushrea.code.runtime.local.AndroidLocalRuntimeMessages
import com.mushrea.code.runtime.local.AntigravityRuntime
import com.mushrea.code.runtime.local.AntigravityTarget
import com.mushrea.code.runtime.local.CustomProviderStore
import com.mushrea.code.runtime.local.DefaultLocalRuntimeUpdateEngine
import com.mushrea.code.runtime.local.GitCredentialHelper
import com.mushrea.code.runtime.local.LocalProviderCredentialStore
import com.mushrea.code.runtime.local.LocalRuntimeAccessCoordinator
import com.mushrea.code.runtime.local.LocalRuntimeCommandRunner
import com.mushrea.code.runtime.local.LocalRuntimeInstaller
import com.mushrea.code.runtime.local.LocalRuntimeManager
import com.mushrea.code.runtime.local.LocalRuntimeMessages
import com.mushrea.code.runtime.local.LocalRuntimeProcessLauncher
import com.mushrea.code.runtime.local.LocalRuntimeReleaseClient
import com.mushrea.code.runtime.local.LocalRuntimeServiceController
import com.mushrea.code.runtime.local.LocalRuntimeTarget
import com.mushrea.code.runtime.local.LocalRuntimeUpdater
import com.mushrea.code.runtime.local.VerifiedRuntimeDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val appModule =
    module {

        single<File> { File(androidContext().filesDir, "runtime") }

        single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

        single { SecureSettingsRepository(androidContext()) }

        single { AppPreferencesRepository(get()) }

        single { DraftRepository(androidContext()) }

        single { RuntimeNotificationHelper(androidContext()) }

        single { AndroidRuntimeActivityMessages(androidContext()) }

        single { AndroidRuntimeCatalogMessages(androidContext()) }

        single { LocalProviderCredentialStore(get()) }

        single { CustomProviderStore(get()) }

        single { VoskModelStore(androidContext(), get(), get()) }

        single { OkHttpClient() }

        single { LocalRuntimeAccessCoordinator() }

        single<LocalRuntimeMessages> { AndroidLocalRuntimeMessages(androidContext()) }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            LocalRuntimeInstaller(
                context = androidContext(),
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                accessCoordinator = get(),
            )
        }

        single {
            val settings: SecureSettingsRepository = get()
            val providerCredentials: LocalProviderCredentialStore = get()
            val customProviders: CustomProviderStore = get()
            val runtimeDirectory: File = get()
            LocalRuntimeProcessLauncher(
                runtimeDirectory = runtimeDirectory,
                portProbe = LocalRuntimeManager::defaultPortProbe,
                githubToken = { settings.githubToken },
                beforeStart = { installed ->
                    runCatching {
                        DeviceAgentStore(get<android.content.Context>())
                            .writeActiveWorkspace(File(runtimeDirectory, "workspace").absolutePath)
                    }
                    runCatching { providerCredentials.syncToRuntime(installed.rootfs) }
                    runCatching { customProviders.syncToRuntime(installed.rootfs) }
                    runCatching {
                        GitCredentialHelper(installed.rootfs) { settings.githubToken }.let { helper ->
                            if (settings.githubToken.isNullOrBlank()) helper.remove() else helper.install()
                        }
                    }
                },
            )
        }

        single {
            val runtimeDirectory: File = get()
            val installer: LocalRuntimeInstaller = get()
            LocalRuntimeCommandRunner(
                runtimeDirectory = runtimeDirectory,
                installedRuntimeProvider = installer::installedRuntime,
                accessCoordinator = get(),
                messages = get(),
            )
        }

        single {
            val runtimeDirectory: File = get()
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val httpClient: OkHttpClient = get()
            val commandRunner: LocalRuntimeCommandRunner = get()
            val verifiedDownloader = VerifiedRuntimeDownloader(httpClient)
            val updater =
                LocalRuntimeUpdater(
                    runtimeDirectory = runtimeDirectory,
                    abi = abi,
                    downloadAsset = { asset, destination, progress ->
                        verifiedDownloader.download(
                            url = asset.url,
                            destination = destination,
                            expectedSha256 = asset.sha256,
                            expectedSizeBytes = asset.sizeBytes,
                            onProgress = progress,
                        )
                    },
                    candidateVersionProvider = { candidate ->
                        val result =
                            commandRunner.runShell(
                                commandText = "/usr/local/bin/${candidate.name} --version",
                                timeoutSeconds = 30L,
                            )
                        require(result.exitCode == 0) {
                            "OpenCode update candidate validation failed: ${result.output}"
                        }
                        result.output.lineSequence().firstOrNull(String::isNotBlank)
                            ?: error("OpenCode update candidate returned no version")
                    },
                    accessCoordinator = get(),
                    messages = get(),
                )
            val updateEngine =
                DefaultLocalRuntimeUpdateEngine(
                    releaseClient = LocalRuntimeReleaseClient(httpClient),
                    updater = updater,
                )
            LocalRuntimeManager(
                runtimeDirectory = runtimeDirectory,
                abi = abi,
                installer = get(),
                processLauncher = get(),
                updateEngine = updateEngine,
                messages = get(),
            )
        }

        single { LocalRuntimeServiceController(androidContext()) }

        single {
            RuntimeRegistry(
                store = get(),
                localTarget = LocalRuntimeTarget(get(), messages = get()),
                additionalTargets =
                    listOf(
                        AntigravityTarget(
                            AntigravityRuntime(get(), (get<LocalRuntimeInstaller>())::installedRuntime),
                        ),
                        // The application's own instance, not a second CodexTarget: Codex runs one
                        // long-lived app-server whose approvals and threads a second runtime would
                        // not see. Resolved lazily, after MushreaCodeApplication.onCreate has built it.
                        (androidContext().applicationContext as MushreaCodeApplication).codexTarget,
                    ),
            )
        }

        single {
            val settings: SecureSettingsRepository = get()
            GitHubApiClient(token = { settings.githubToken }, client = get())
        }

        single {
            PullRequestStatusRepository(api = get(), scope = get())
        }

        single {
            RuntimeCatalogRepository(get(), get(), messages = get<AndroidRuntimeCatalogMessages>())
        }

        single {
            val notifications: RuntimeNotificationHelper = get()
            RuntimeActivityRepository(
                registry = get(),
                scope = get(),
                onPermissionAsked = { request, title, runtimeId ->
                    notifications.notifyPermission(request, title, runtimeId)
                },
                onSessionIdle = { sessionId, title, runtimeId ->
                    notifications.notifySessionComplete(sessionId, title, runtimeId)
                },
                onSessionError = { sessionId, message, runtimeId ->
                    notifications.notifySessionError(sessionId, message, runtimeId)
                },
                onQuestionAsked = { request, title, runtimeId ->
                    notifications.notifyQuestion(request, title, runtimeId)
                },
                messages = get<AndroidRuntimeActivityMessages>(),
            )
        }
    }
