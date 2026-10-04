package com.mushrea.code.core.execution

import com.mushrea.code.core.permission.PermissionRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recipe catalogue is data, so these tests are about its *shape*: every objective declares
 * parameters it can build from, every candidate names the capability it needs, and a parameter that
 * must be an identifier is validated rather than pasted into a command.
 *
 * The catalogue is also the answer to "does the platform need a new tool for this?": as long as a
 * recipe exists (or a shell line reaches it), the agent does not.
 */
class ExecutionRecipesTest {
    /** A plausible value for every parameter name the catalogue uses. */
    private val values =
        mapOf(
            "prefix" to "ro.build",
            "package" to "com.android.settings",
            "component" to "com.android.settings/.Settings",
            "apk" to "/sdcard/Download/app.apk",
            "remote" to "/sdcard/mushrea-out",
            "local" to "/home/user/out",
            "path" to "/sdcard/Download",
            "x" to "100",
            "y" to "200",
            "x1" to "100",
            "y1" to "200",
            "x2" to "100",
            "y2" to "900",
            "duration_ms" to "300",
            "text" to "hello world",
            "keycode" to "KEYCODE_BACK",
            "lines" to "50",
            "namespace" to "system",
            "key" to "screen_brightness",
            "value" to "128",
            "script" to "echo hello",
            "interpreter" to "sh",
            "shell_fallback" to "yes",
            "program" to "id",
            "arguments" to "-a",
            "command" to "echo hello",
            "port" to "5555",
        )

    private fun parametersOf(recipe: ExecutionRecipe): Map<String, String> =
        recipe.parameters.mapNotNull { parameter -> values[parameter.name]?.let { parameter.name to it } }.toMap()

    @Test
    fun `every recipe builds at least one candidate from its own parameters`() {
        assertTrue("the catalogue is not empty", ExecutionRecipes.all.size >= 30)
        assertEquals("ids are unique", ExecutionRecipes.all.size, ExecutionRecipes.all.map { it.id }.toSet().size)
        ExecutionRecipes.all.forEach { recipe ->
            val candidates = recipe.candidates(parametersOf(recipe))
            assertTrue("${recipe.id} builds no candidate", candidates.isNotEmpty())
            candidates.forEach { candidate -> assertTrue("${candidate.id} has no action", candidate.actions.isNotEmpty()) }
            assertTrue("${recipe.id} has no title", recipe.title.isNotBlank())
            assertTrue("${recipe.id} has no keywords", recipe.keywords.isNotEmpty())
            assertTrue("${recipe.id} has no description", recipe.description.isNotBlank())
        }
    }

    @Test
    fun `the provisioning objectives are recipes, not a separate path`() {
        val ids = ExecutionRecipes.all.map { it.id }
        val provisioning = listOf("provision.stay_awake", "provision.wifi_alive", "provision.wireless_debugging", "provision.adb_port")
        assertTrue("the provisioning objectives are in the same catalogue", ids.containsAll(provisioning))

        // A recipe is reachable by naming it or by describing the goal, and an owner describes it in
        // their own words - Arabic included.
        assertEquals("provision.stay_awake", ExecutionRecipes.registry.byId("provision.stay_awake")!!.id)
        assertEquals("provision.stay_awake", ExecutionRecipes.registry.match("أبق الهاتف مستيقظا")!!.id)
        assertEquals("provision.wifi_alive", ExecutionRecipes.registry.match("keep wifi on while the phone sleeps")!!.id)
        assertEquals("provision.wireless_debugging", ExecutionRecipes.registry.match("التصحيح اللاسلكي")!!.id)
    }

    @Test
    fun `a provisioning step that changes the phone carries the proof of its effect`() {
        listOf("provision.stay_awake", "provision.wifi_alive", "provision.adb_port").forEach { id ->
            val candidate = ExecutionRecipes.registry.byId(id)!!.candidates(mapOf("port" to "5555")).single()
            val action = candidate.actions.single()
            assertTrue("$id changes the phone, so it must say so", action.effect.mutatesTarget)
            assertTrue("$id must say how its effect is proven", action.verify.isNotBlank())
            assertTrue("$id names the capability it uses", action.capability.isNotBlank())
        }

        // The fixed-port write is the one that needs root on a modern build, and the requirement says
        // so - which is what makes the planner skip it on a phone that reported no `su` instead of
        // failing the request.
        val portCandidate = ExecutionRecipes.registry.byId("provision.adb_port")!!.candidates(mapOf("port" to "5555")).single()
        assertEquals(listOf("priv:su"), portCandidate.requires.map { it.name })
        assertTrue("the note explains why the daemon is not restarted", portCandidate.note.contains("restart"))

        // And a port that is not a port is refused before it can become a command.
        val recipe = ExecutionRecipes.registry.byId("provision.adb_port")!!
        val thrown = runCatching { recipe.candidates(mapOf("port" to "5555; rm -rf /sdcard")) }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `reading the wireless-debugging switch is read-only and names the settings capability`() {
        val candidate = ExecutionRecipes.registry.byId("provision.wireless_debugging")!!.candidates(emptyMap()).single()

        assertFalse(candidate.actions.single().effect.mutatesTarget)
        assertEquals("bin:settings", candidate.actions.single().capability)
        assertEquals(listOf("bin:settings"), candidate.requires.map { it.name })
    }

    @Test
    fun `a recipe whose required parameter is missing fails loudly instead of building a command`() {
        val recipe = ExecutionRecipes.registry.byId("files.read")
        assertNotNull(recipe)
        assertEquals(listOf("path"), recipe!!.missingParameters(emptyMap()).map { it.name })

        val thrown = runCatching { recipe.candidates(emptyMap()) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `listing packages offers pm first and cmd second, each naming its capability`() {
        val candidates = ExecutionRecipes.registry.byId("packages.list")!!.candidates(emptyMap())

        assertEquals(listOf("pm-list", "cmd-package-list"), candidates.map { it.id })
        assertEquals(listOf(CapabilityNames.binary("pm"), CapabilityNames.binary("cmd")), candidates.map { it.primaryCapability })
        assertTrue(candidates.all { candidate -> candidate.requires.single().name.startsWith("bin:") })
    }

    @Test
    fun `a package name is validated, so a semicolon cannot become a second command`() {
        val recipe = ExecutionRecipes.registry.byId("packages.info")!!

        val thrown = runCatching { recipe.candidates(mapOf("package" to "com.x; rm -rf /sdcard")) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a tap is built from numbers and refuses anything else`() {
        val recipe = ExecutionRecipes.registry.byId("input.tap")!!

        val tap = recipe.candidates(mapOf("x" to "100", "y" to "200")).single().actions.single()
        assertEquals("input tap 100 200", tap.invocation.command)
        assertTrue(tap.effect.mutatesTarget)
        assertTrue(runCatching { recipe.candidates(mapOf("x" to "1; reboot", "y" to "2")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `typed text keeps its spaces as the placeholder the device understands`() {
        val action =
            ExecutionRecipes.registry
                .byId("input.text")!!
                .candidates(mapOf("text" to "hello world"))
                .single()
                .actions
                .single()

        assertEquals("input text 'hello%sworld'", action.invocation.command)
    }

    @Test
    fun `a screen capture is a capture plus a pull, and names both capabilities`() {
        val candidate = ExecutionRecipes.registry.byId("screen.capture")!!.candidates(mapOf("local" to "/tmp/s.png"))

        val actions = candidate.single().actions
        assertEquals(listOf(ExecutionOperation.SHELL, ExecutionOperation.PULL), actions.map { it.operation })
        assertEquals(CapabilityNames.binary("screencap"), actions.first().capability)
        assertEquals(CapabilityNames.SYNC, actions.last().capability)
        assertEquals("/tmp/s.png", actions.last().invocation.files.single().localPath)
    }

    @Test
    fun `deleting a file is declared destructive and high risk`() {
        val action =
            ExecutionRecipes.registry
                .byId("files.remove")!!
                .candidates(mapOf("path" to "/sdcard/x"))
                .single()
                .actions
                .single()

        assertTrue(action.effect.mutatesTarget)
        assertTrue(action.effect.destructive)
        assertEquals(PermissionRisk.HIGH, action.effect.risk)
    }

    @Test
    fun `a script needs the interpreter it names, and only falls back when the caller allows it`() {
        val python =
            ExecutionRecipes.registry
                .byId("script.run")!!
                .candidates(mapOf("script" to "print(1)", "interpreter" to "python3"))

        assertEquals(listOf("script-with-python3"), python.map { it.id })
        assertEquals(listOf("interp:python3", CapabilityNames.binary("python3")), python.single().requires.single().names)

        val allowed =
            ExecutionRecipes.registry
                .byId("script.run")!!
                .candidates(mapOf("script" to "echo hi", "interpreter" to "python3", "shell_fallback" to "yes"))

        assertEquals(listOf("script-with-python3", "script-with-sh"), allowed.map { it.id })
        assertEquals(listOf(CapabilityNames.binary("sh"), CapabilityNames.SHELL), allowed.last().requires.single().names)
    }

    @Test
    fun `installing an APK has an adb route and a route that uses the phone's own package manager`() {
        val candidates = ExecutionRecipes.registry.byId("app.install")!!.candidates(mapOf("apk" to "/sdcard/app.apk"))

        assertEquals(listOf("adb-install", "push-then-pm-install"), candidates.map { it.id })
        assertEquals(ExecutionOperation.INSTALL, candidates.first().actions.single().operation)
        assertEquals(listOf(ExecutionOperation.PUSH, ExecutionOperation.SHELL), candidates.last().actions.map { it.operation })
        assertTrue(candidates.first().actions.single().effect.destructive)
    }

    @Test
    fun `the catalogue can be matched by words, in English and in Arabic`() {
        val registry = ExecutionRecipes.registry

        assertEquals("screen.capture", registry.match("take a screenshot of the phone")?.id)
        assertEquals("packages.list", registry.match("اعرض التطبيقات المثبتة")?.id)
        assertEquals("script.run", registry.match("نفذ هذا السكربت")?.id)
        assertNotNull(registry.byId("shell.run"))
    }

    @Test
    fun `a registered recipe does not disturb the shipped ones`() {
        val extra =
            ExecutionRecipe(
                id = "vendor.ping",
                title = "Ping the vendor service",
                description = "test",
                keywords = listOf("vendor ping"),
            ) {
                listOf(
                    RecipeCandidate(
                        id = "direct",
                        actions =
                            listOf(
                                RecipeAction(
                                    operation = ExecutionOperation.SHELL,
                                    invocation = ExecutionInvocation("vendor ping"),
                                    effect = ExecutionEffect(mutatesTarget = false),
                                    capability = "bin:vendor",
                                ),
                            ),
                    ),
                )
            }

        val registry = ExecutionRecipes.registry.with(extra)

        assertNotNull(registry.byId("vendor.ping"))
        assertNotNull(registry.byId("packages.list"))
        assertFalse(ExecutionRecipes.all.any { it.id == "vendor.ping" })
    }
}
