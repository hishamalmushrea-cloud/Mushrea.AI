package com.mushrea.code.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mushrea.code.MushreaCodeApplication
import com.mushrea.code.core.workspace.WorkspaceRef
import com.mushrea.code.feature.browser.GuestBrowserScreen
import com.mushrea.code.feature.devices.PeerDevicesScreen
import com.mushrea.code.feature.devices.PeerDevicesViewModel
import com.mushrea.code.feature.workspace.CodeViewerScreen
import com.mushrea.code.feature.workspace.CodeViewerViewModel
import com.mushrea.code.feature.workspace.LocalRuntimeManagementScreen
import com.mushrea.code.feature.workspace.LocalRuntimeManagementViewModel
import com.mushrea.code.feature.workspace.RemoteConnectionScreen
import com.mushrea.code.feature.workspace.TerminalScreen
import com.mushrea.code.feature.workspace.TerminalViewModel
import com.mushrea.code.feature.workspace.WorkspaceExplorerScreen
import com.mushrea.code.feature.workspace.WorkspaceExplorerViewModel
import com.mushrea.code.feature.workspace.WorkspaceViewModel
import com.mushrea.code.feature.workspace.WorkspacesScreen
import com.mushrea.code.feature.workspace.isOpenable
import com.mushrea.code.runtime.RuntimeTarget
import com.mushrea.code.ui.ViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun NavGraphBuilder.workspaceNavGraph(
    navController: NavController,
    workspaceViewModel: WorkspaceViewModel,
    // Getters, not values: NavHost remembers the destination lambdas, so a value read here is the
    // one that existed when the graph was built. That is how the explorer came to be unreachable —
    // the workspace the user had just tapped still read as null and the screen popped straight back.
    selectedWorkspace: () -> WorkspaceRef?,
    onSelectWorkspace: (WorkspaceRef?) -> Unit,
    selectedRuntime: () -> RuntimeTarget?,
    app: MushreaCodeApplication,
    onImportFolder: () -> Unit,
    onShowCloneDialog: () -> Unit,
    completeOnboardingAndGoToChat: () -> Unit,
) {
    composable(ROUTE_REMOTE_CONNECTION) {
        RemoteConnectionScreen(
            onTestConnection = workspaceViewModel::testConnection,
            // Saving here is the user pressing "connect", so the PC becomes the active runtime even
            // when an Android-local runtime is already set up and selected.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = true) },
            onBack = { navController.popBackStack() },
            onConnected = completeOnboardingAndGoToChat,
        )
    }

    composable(ROUTE_WORKSPACES) {
        // Collected here rather than passed in: NavHost remembers the graph, so a state value
        // handed to this builder would stay frozen at whatever it was on first composition and
        // every later update — runtime installs, health checks — would never reach the screen.
        val workspaceState by workspaceViewModel.state.collectAsState()
        WorkspacesScreen(
            state = workspaceState,
            onSelectRuntime = workspaceViewModel::selectRuntime,
            // This screen edits the connection list and has its own per-target "select" action, so
            // saving must not move the running target under the user.
            onSaveConnection = { form -> workspaceViewModel.saveConnection(form, activate = false) },
            onDeleteConnection = workspaceViewModel::deleteConnection,
            onTestConnection = workspaceViewModel::testConnection,
            onRefresh = workspaceViewModel::refresh,
            onOpenWorkspace = { workspace ->
                onSelectWorkspace(workspace)
                navController.navigate(WORKSPACE_DETAIL_ROUTE)
            },
            onImportFolder = onImportFolder,
            onCloneGithub = onShowCloneDialog,
            onChooseFolder = workspaceViewModel::openFolderPicker,
            onBrowseFolderInto = workspaceViewModel::browseFolderInto,
            onBrowseFolderUp = workspaceViewModel::browseFolderUp,
            onConfirmFolder = { workspaceViewModel.confirmFolderPicker() },
            onDismissFolderPicker = workspaceViewModel::dismissFolderPicker,
            onDeviceStorageAccessChanged = workspaceViewModel::onDeviceStorageAccessChanged,
            onRemoveProject = workspaceViewModel::removeProject,
            onDeleteProjectFiles = workspaceViewModel::deleteProjectFiles,
            onDismissDeleteFailure = workspaceViewModel::dismissDeleteFailure,
            onBack = { navController.popBackStack() },
        )
    }

    composable(LOCAL_RUNTIME_MANAGEMENT_ROUTE) {
        val managementViewModel: LocalRuntimeManagementViewModel =
            viewModel(
                key = "local-runtime-management",
                factory =
                    ViewModelFactory {
                        LocalRuntimeManagementViewModel(
                            runtimeState = app.localRuntimeManager.state,
                            lastOperationState = app.localRuntimeManager.lastOperation,
                            diagnosticsProvider = {
                                withContext(Dispatchers.IO) {
                                    app.localRuntimeDiagnosticsCollector.collect()
                                }
                            },
                            repairAction = app.localRuntimeController::reinstall,
                            installFullDevelopmentToolsAction = app.localRuntimeController::installFullDevelopmentTools,
                            runtimeEnvironmentInstalledProvider = app.localRuntimeManager::runtimeEnvironmentInstalled,
                            fullDevelopmentToolsInstalledProvider = app.localRuntimeManager::fullDevelopmentToolsInstalled,
                            deleteAction = app.localRuntimeController::delete,
                            getString = { app.getString(it) },
                            adbState = app.adbConnectionManager.state,
                            adbPairAction = app.adbConnectionManager::pair,
                            adbConnectAction = app.adbConnectionManager::connect,
                            adbDisconnectAction = app.adbConnectionManager::disconnect,
                            adbStartDiscovery = app.adbConnectionManager::startDiscovery,
                        )
                    },
            )
        val managementState by managementViewModel.state.collectAsState()
        LaunchedEffect(managementState.deleteCompleted) {
            if (managementState.deleteCompleted) {
                managementViewModel.consumeDeleteCompleted()
                workspaceViewModel.refresh()
                navController.popBackStack()
            }
        }
        LocalRuntimeManagementScreen(
            state = managementState,
            onBack = { navController.popBackStack() },
            onRefresh = managementViewModel::refresh,
            onRepair = managementViewModel::repair,
            onInstallFullDevelopmentTools = managementViewModel::installFullDevelopmentTools,
            onRequestDelete = managementViewModel::requestDelete,
            onDismissDelete = managementViewModel::dismissDelete,
            onConfirmDelete = managementViewModel::confirmDelete,
            onShowAdbPairDialog = managementViewModel::showAdbPairDialog,
            onDismissAdbPairDialog = managementViewModel::dismissAdbPairDialog,
            onAdbPair = managementViewModel::adbPair,
            onAdbConnect = managementViewModel::adbConnect,
            onAdbDisconnect = managementViewModel::adbDisconnect,
        )
    }

    composable(PEER_DEVICES_ROUTE) {
        // The bridge is resolved lazily: the graph can be built before the application finishes
        // initialising, and the view model reports that state instead of crashing on a lateinit.
        val peerViewModel: PeerDevicesViewModel =
            viewModel(
                key = "peer-devices",
                factory =
                    ViewModelFactory {
                        PeerDevicesViewModel(
                            bridgeProvider = { runCatching { app.peerAdbBridge }.getOrNull() },
                            getString = { app.getString(it) },
                            provisioningProvider = { runCatching { app.peerProvisioning }.getOrNull() },
                        )
                    },
            )
        val peerState by peerViewModel.state.collectAsState()
        // Re-read the registry when the screen is composed: a pairing started from the agent's tools,
        // or a connection dropped by the other phone, has to show up here rather than on the next
        // navigation. Refreshing again is cheap - it reads the stored registry, it does not probe.
        LaunchedEffect(Unit) { peerViewModel.refresh() }
        PeerDevicesScreen(
            state = peerState,
            onBack = { navController.popBackStack() },
            onRefresh = peerViewModel::refresh,
            onStartQrPairing = peerViewModel::startQrPairing,
            onDismissPairing = peerViewModel::dismissPairing,
            onShowCodeDialog = peerViewModel::showCodeDialog,
            onDismissCodeDialog = peerViewModel::dismissCodeDialog,
            onCodeHostChange = peerViewModel::updateCodeHost,
            onCodePortChange = peerViewModel::updateCodePort,
            onCodeChange = peerViewModel::updateCode,
            onPairWithCode = peerViewModel::pairWithCode,
            onConnect = peerViewModel::connect,
            onDisconnect = peerViewModel::disconnect,
            onProvision = peerViewModel::provision,
            onRefreshCapabilities = peerViewModel::refreshCapabilities,
            onAskForget = peerViewModel::askForget,
            onDismissForget = peerViewModel::dismissForget,
            onConfirmForget = peerViewModel::confirmForget,
            onMessageShown = peerViewModel::clearMessage,
        )
    }

    composable(WORKSPACE_DETAIL_ROUTE) {
        val workspace = selectedWorkspace()
        val runtime = selectedRuntime()
        if (workspace == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val explorerViewModel: WorkspaceExplorerViewModel =
                viewModel(
                    key = "workspace-explorer-${runtime.id}-${workspace.id}",
                    factory =
                        ViewModelFactory {
                            WorkspaceExplorerViewModel(runtime, workspace)
                        },
                )
            val explorerState by explorerViewModel.state.collectAsState()
            WorkspaceExplorerScreen(
                state = explorerState,
                onBack = { navController.popBackStack() },
                onRefresh = explorerViewModel::refresh,
                onOpenNode = { node ->
                    if (node.type == "directory") {
                        explorerViewModel.open(node)
                    } else {
                        // node.path is relative to the directory currently being browsed
                        // (explorerState.currentPath), not to the fixed workspace.path - the
                        // explorer can now navigate outside the original workspace root.
                        navController.navigate(codeViewerRoute(runtime.id, explorerState.currentPath, node.path))
                    }
                },
                onNavigateUp = explorerViewModel::navigateUp,
                onSearch = explorerViewModel::search,
                onRefreshChanges = explorerViewModel::refreshChanges,
                onOpenTerminal = { navController.navigate(ROUTE_TERMINAL) },
                onOpenChange = { change ->
                    if (change.isOpenable()) {
                        navController.navigate(codeViewerRoute(runtime.id, workspace.path, change.displayPath))
                    }
                },
            )
        }
    }

    composable(ROUTE_TERMINAL) {
        val terminalViewModel: TerminalViewModel =
            viewModel(
                key = "terminal",
                factory =
                    ViewModelFactory {
                        TerminalViewModel(app.commandRunner, app.runtimeWork)
                    },
            )
        val terminalState by terminalViewModel.state.collectAsState()
        TerminalScreen(
            state = terminalState,
            onCommand = terminalViewModel::executeCommand,
            onInputChange = terminalViewModel::updateInput,
            onClear = terminalViewModel::clear,
            onHistoryUp = terminalViewModel::historyUp,
            onHistoryDown = terminalViewModel::historyDown,
            onStop = terminalViewModel::stop,
        )
    }

    composable(GUEST_BROWSER_ROUTE_PATTERN) { backStack ->
        val requestedUrl = backStack.arguments?.getString(GUEST_BROWSER_ARG_URL)?.let { decodeRouteArg(it) }
        GuestBrowserScreen(
            initialUrl =
                requestedUrl
                    ?: app.localRuntimeManager.installedPort()?.let { "http://127.0.0.1:$it/" }.orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }

    composable(CODE_VIEWER_ROUTE_PATTERN) { backStack ->
        val arguments =
            runCatching {
                Triple(
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("runtimeId"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("workspacePath"))),
                    decodeRouteArg(requireNotNull(backStack.arguments?.getString("filePath"))),
                )
            }.getOrNull()
        val runtime = arguments?.first?.let { app.runtimeRegistry.target(it) }
        if (arguments == null || runtime == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
        } else {
            val viewerViewModel: CodeViewerViewModel =
                viewModel(
                    key = "code-viewer-${backStack.id}",
                    factory = ViewModelFactory { CodeViewerViewModel(runtime, arguments.second, arguments.third) },
                )
            val viewerState by viewerViewModel.state.collectAsState()
            val preferences by app.preferences.state.collectAsState()
            CodeViewerScreen(
                state = viewerState,
                syntaxThemeKey = preferences.syntaxTheme,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
