package com.mushrea.code.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * The Accessibility Engine (prompt section 7): once the user grants the accessibility permission,
 * this service can read the current window's node tree, find elements semantically, tap, scroll,
 * type, and perform the global Back/Home/Recents actions — while also tracking which app is in the
 * foreground so the Context Engine always knows where the user is.
 *
 * It also hosts the [DeviceAgentBridge], which consumes `.mushrea-code/device-command.json` files
 * dropped by the agent's device MCP server, so device tools keep working while the user is inside
 * any other app (prompt section 31 — official foreground/background service, no Android bypasses).
 */
class MushreaCodeAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: DeviceAgentBridge? = null

    private lateinit var contextStore: DeviceAgentStore

    /** Debounce for context writes from window-change events (they can be very chatty). */
    private var lastContextWriteAtMillis = 0L

    private var lastContextPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE.set(this)
        contextStore = DeviceAgentStore(applicationContext)
        bridge =
            DeviceAgentBridge(
                context = applicationContext,
                store = contextStore,
                engine = Engine(),
            ).also { it.start() }
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        INSTANCE.set(null)
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString().orEmpty()
                if (pkg.isNotBlank()) {
                    currentPackage.set(pkg)
                    val activity = event.className?.toString()?.takeIf { it.contains('.') }?.substringAfterLast('.')
                    if (activity != null) currentActivity.set(activity)
                    // Feed the Context Engine (prompt sections 8/9): track the app trail as the
                    // user (or the agent) moves between apps, debounced to one write per switch.
                    if (
                        ::contextStore.isInitialized &&
                        pkg != lastContextPackage &&
                        System.currentTimeMillis() - lastContextWriteAtMillis > CONTEXT_WRITE_DEBOUNCE_MILLIS
                    ) {
                        lastContextPackage = pkg
                        lastContextWriteAtMillis = System.currentTimeMillis()
                        scope.launch { contextStore.updateContextApp(pkg, activity) }
                    }
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    // region Shared state

    val currentPackage = AtomicReference("")

    val currentActivity = AtomicReference("")

    // endregion

    /**
     * Walks the active window's tree and converts it into a plain [ScreenSnapshot]; everything
     * after this point (matching, formatting) is pure logic and unit-tested off-device.
     */
    fun snapshot(maxElements: Int = DEFAULT_MAX_ELEMENTS): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val elements = ArrayList<ScreenElement>(maxElements)
        var truncated = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && elements.size < maxElements && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val className = node.className?.toString().orEmpty()
            val interesting =
                (text.isNotBlank() || description.isNotBlank()) &&
                    node.isVisibleToUser &&
                    rect.width() > 0 && rect.height() > 0
            if (interesting) {
                elements +=
                    ScreenElement(
                        index = elements.size,
                        text = text,
                        contentDescription = description,
                        className = className,
                        viewIdResourceName = node.viewIdResourceName.orEmpty(),
                        boundsInScreen =
                            ScreenElement.Rect(rect.left, rect.top, rect.right, rect.bottom),
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                    )
            }
            if (elements.size < maxElements) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } else if (queue.isNotEmpty()) {
                truncated = true
            }
        }
        if (queue.isNotEmpty()) truncated = true
        return ScreenSnapshot(
            packageName = currentPackage.get().ifBlank { root.packageName?.toString().orEmpty() },
            activity = currentActivity.get().ifBlank { null },
            elements = elements,
            truncated = truncated,
        )
    }

    /** Re-locates the live node matching [element] in the current tree (by bounds + label). */
    fun locateNode(element: ScreenElement): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val sameBounds =
                rect.left == element.boundsInScreen.left &&
                    rect.top == element.boundsInScreen.top &&
                    rect.right == element.boundsInScreen.right &&
                    rect.bottom == element.boundsInScreen.bottom
            if (sameBounds) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Clicks [node], walking up to a clickable ancestor when the node itself is not clickable. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_CLICKABLE_ANCESTORS) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            hops++
        }
        return false
    }

    /** Sets or replaces the text of an editable node. */
    fun setNodeText(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean =
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            },
        )

    fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: run {
                // Some IMEs detach focus; fall back to the first editable visible field.
                snapshot(maxElements = DEFAULT_MAX_ELEMENTS)?.elements
                    ?.firstOrNull { it.editable }
                    ?.let { locateNode(it) }
            }
    }

    fun performGlobal(action: String): Boolean =
        when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> false
        }

    /** Taps an absolute screen point with a synthetic gesture (coordinate fallback — prompt section 6). */
    fun tapPoint(
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()
        return dispatchGestureSync(gesture)
    }

    fun longPressPoint(
        x: Float,
        y: Float,
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, LONG_PRESS_DURATION_MS))
                .build()
        return dispatchGestureSync(gesture)
    }

    fun swipePoints(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean {
        val path =
            Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        return dispatchGestureSync(gesture)
    }

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val result = CompletableDeferred<Boolean>()
        val delivered =
            runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            result.complete(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            result.complete(false)
                        }
                    },
                    gestureCallbackHandler,
                )
            }.getOrDefault(false)
        if (!delivered) return false
        return runCatching { kotlinx.coroutines.runBlocking { result.await() } }.getOrDefault(false)
    }

    /**
     * The Device Agent's actuation surface. All calls must run on the main thread (accessibility
     * node access is not thread-safe), which the bridge guarantees with `withContext(Main)`.
     */
    inner class Engine {
        fun isReady(): Boolean = INSTANCE.get() === this@MushreaCodeAccessibilityService && rootInActiveWindow != null

        fun currentApp(): Pair<String, String?> {
            val pkg = currentPackage.get().ifBlank { rootInActiveWindow?.packageName?.toString().orEmpty() }
            return pkg to currentActivity.get().ifBlank { null }
        }

        fun readScreen(maxElements: Int = DEFAULT_MAX_ELEMENTS): ScreenSnapshot? = snapshot(maxElements)

        fun tap(
            x: Float,
            y: Float,
        ): Boolean = tapPoint(x, y)

        fun longPress(
            x: Float,
            y: Float,
        ): Boolean = longPressPoint(x, y)

        fun swipe(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ): Boolean = swipePoints(x1, y1, x2, y2, durationMs)

        fun global(action: String): Boolean = performGlobal(action)

        fun typeIntoFocused(text: String): Boolean {
            val node = focusedEditable() ?: return false
            return setNodeText(node, text)
        }

        fun appendToFocused(text: String): Boolean {
            val node = focusedEditable() ?: return false
            val existing = node.text?.toString().orEmpty()
            return setNodeText(node, existing + text)
        }

        fun clearFocused(): Boolean {
            val node = focusedEditable() ?: return false
            return setNodeText(node, "")
        }

        fun openAppIntent(packageName: String): Intent? = packageManager.getLaunchIntentForPackage(packageName)
    }

    companion object {
        /** The live instance, or null while the user has not enabled the service in system settings. */
        val INSTANCE = AtomicReference<MushreaCodeAccessibilityService?>(null)

        fun isRunning(): Boolean = INSTANCE.get() != null

        internal const val DEFAULT_MAX_ELEMENTS = 120
        internal const val MAX_VISITED_NODES = 600
        internal const val MAX_CLICKABLE_ANCESTORS = 6
        internal const val TAP_DURATION_MS = 60L
        internal const val LONG_PRESS_DURATION_MS = 600L
        internal const val CONTEXT_WRITE_DEBOUNCE_MILLIS = 1_500L
    }
}
