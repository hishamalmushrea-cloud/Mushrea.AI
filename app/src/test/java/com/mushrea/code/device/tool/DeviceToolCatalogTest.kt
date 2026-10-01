package com.mushrea.code.device.tool

import com.mushrea.code.device.ConfirmationLevel
import com.mushrea.code.device.DeviceActionFirewall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool catalog is the app-side truth for the device surface, so these tests are about the
 * properties that must never quietly change: how many tools exist, which of them ask the user first,
 * that a dangerous tool is never silent, and that the firewall and the agent-facing names both come
 * from this one table.
 *
 * The cross-checks against the shipped MCP script live in `scripts/check_tool_catalog.py`, which reads
 * Python and Kotlin together and runs in CI; a JVM test cannot import that script.
 */
class DeviceToolCatalogTest {
    private val all = DeviceToolCatalog.all

    @Test
    fun `every tool has an identity, a purpose and a policy`() {
        assertEquals(90, all.size)
        assertEquals("ids must be unique", all.size, all.map { it.id }.toSet().size)
        for (tool in all) {
            assertTrue("${tool.id}: empty id", tool.id.isNotBlank())
            assertTrue("${tool.id}: empty purpose", tool.purpose.isNotBlank())
            assertTrue("${tool.id}: declares no requirement", tool.requires.isNotEmpty())
            assertTrue("${tool.id}: no timeout", tool.timeoutMillis > 0 || tool.transport == ToolTransport.WORKSPACE_FILE)
        }
    }

    @Test
    fun `the bridge actions are the firewall's action list, both ways`() {
        assertEquals(89, DeviceToolCatalog.actions.size)
        assertEquals(DeviceToolCatalog.actions, DeviceActionFirewall.ALL_ACTIONS)
        assertEquals(DeviceToolCatalog.autoActions, DeviceActionFirewall.AUTO_ACTIONS)
        assertEquals(DeviceToolCatalog.confirmActions, DeviceActionFirewall.CONFIRM_ACTIONS)
        assertEquals(DeviceToolCatalog.readOnlyActions, DeviceActionFirewall.READ_ONLY_ACTIONS)
    }

    @Test
    fun `every action is either automatic or confirmed, never both`() {
        val auto = DeviceToolCatalog.autoActions
        val confirm = DeviceToolCatalog.confirmActions

        assertTrue("auto and confirm overlap", (auto intersect confirm).isEmpty())
        assertEquals(DeviceToolCatalog.actions, auto + confirm)
        assertEquals(57, auto.size)
        assertEquals(32, confirm.size)

        for (id in DeviceToolCatalog.actions) {
            val expected = if (id in confirm) ConfirmationLevel.CONFIRM else ConfirmationLevel.AUTO
            assertEquals(id, expected, DeviceToolCatalog.confirmationFor(id))
            assertEquals(id, expected, DeviceActionFirewall().levelFor(id))
        }
    }

    /**
     * The list the read-only switch is allowed to keep working with. It is written out here on
     * purpose: adding or removing a name is a safety decision, not a refactor.
     */
    @Test
    fun `read-only mode allows exactly the reader list`() {
        val expected =
            setOf(
                "get_current_app",
                "read_screen",
                "find_element",
                "list_apps",
                "search_files",
                "find_contact",
                "call_state",
                "read_call_log",
                "ping",
                "device_status",
                "call_summaries",
                "set_task",
                "stop_agent",
                "usb_devices",
                "usb_list",
                "usb_info",
                "usb_serial_read",
                "ssh_list",
                "payload_info",
                "fastboot_getvar",
                "usb_hub_list",
                "storage_volumes",
                "camera_list",
                "net_browse",
                "remote_list",
                "mtp_list",
                "wifi_info",
                "dns_lookup",
                "net_ping",
                "port_check",
                "bt_info",
                "bt_devices",
                "bt_scan",
                "ble_scan",
                "usb_mode",
                "usb_diagnostics",
                "payload_guard",
                "safety_preflight",
                "termux_status",
            )

        assertEquals(expected, DeviceToolCatalog.readOnlyActions)
        for (id in expected) {
            assertTrue("$id must be allowed with read-only on", DeviceActionFirewall.isAllowedInReadOnly(id))
            assertFalse("$id is not a tool anymore", DeviceToolCatalog.tool(id) == null)
        }
    }

    /**
     * The safety rule this phase had to repair, and the most important test in this file.
     *
     * `DeviceActionFirewall.levelFor` used to answer AUTO for any id that was in neither of its two
     * hand-written sets. Thirteen actions - the USB/SSH shell, install and transfer family - were in
     * neither, so they ran with no confirmation at all while `DeviceCommandCodecTest` already
     * declared them to be confirmation-class. They are declared CONFIRM in the catalog now, and this
     * list is written out so that removing one is a visible decision rather than a quiet regression.
     */
    @Test
    fun `exactly these tools ask the user first`() {
        val expected =
            setOf(
                // acts on another device or the user's server
                "usb_shell",
                "usb_pull",
                "usb_push",
                "usb_install",
                "usb_logcat",
                "usb_screenshot",
                "usb_transfer_media",
                "usb_serial_send",
                "usb_tcpip_enable",
                "tcp_shell",
                "ssh_exec",
                "ssh_download",
                "ssh_upload",
                "mtp_download",
                "hid_read",
                "remote_download",
                "mirror_start",
                "scrcpy_start",
                "scrcpy_stop",
                // changes this phone or leaves it
                "delete_file",
                "share_file",
                "move_file",
                "copy_file",
                "rename_file",
                "call_agent",
                "http_request",
                "websocket",
                "audit_export",
                "termux_run",
                "termux_fastboot_run",
                "mitool_wrapper",
                "fastboot_getvar_full",
            )

        assertEquals(32, expected.size)
        assertEquals(expected, DeviceToolCatalog.confirmActions)
        val firewall = DeviceActionFirewall(emptyMap())
        for (id in expected) {
            assertEquals(id, ConfirmationLevel.CONFIRM, firewall.levelFor(id))
        }
    }

    @Test
    fun `a high-risk tool always asks the user first`() {
        val high = all.filter { it.risk == ToolRisk.HIGH }

        assertTrue("no high-risk tool at all would mean the risk model is unused", high.isNotEmpty())
        for (tool in high) {
            assertEquals(tool.id, ConfirmationLevel.CONFIRM, tool.confirmation)
        }
    }

    @Test
    fun `a confirmed tool waits longer than the bridge's confirmation window`() {
        // DeviceAgentBridge.CONFIRMATION_TIMEOUT_MILLIS: the app keeps asking for this long, so an
        // agent that gave up sooner would order an action it never reads the result of.
        val bridgeWindow = 120_000L

        for (tool in all.filter { it.confirmation == ConfirmationLevel.CONFIRM }) {
            assertTrue("${tool.id}: waits ${tool.timeoutMillis} ms", tool.timeoutMillis > bridgeWindow)
        }
    }

    @Test
    fun `read-only never blocks on a confirmation`() {
        for (id in DeviceToolCatalog.readOnlyActions) {
            assertEquals(id, ConfirmationLevel.AUTO, DeviceToolCatalog.confirmationFor(id))
        }
    }

    @Test
    fun `the agent-facing names come from this table`() {
        // 88 shipped names, of which device_press covers three actions and device_current_app has a
        // twin (device_bridge_status); the only action without an agent-facing name is the readiness
        // probe.
        val byName = DeviceToolCatalog.mcpTools.groupingBy { it }.eachCount()

        assertEquals(88, byName.size)
        assertEquals(3, byName["device_press"])
        assertEquals(emptyList<String>(), DeviceToolCatalog.tool("ping")?.mcpTools)
        assertEquals(3, DeviceToolCatalog.toolsForMcp("device_press").size)
        assertEquals(listOf("open_recents", "press_back", "press_home"), DeviceToolCatalog.toolsForMcp("device_press").map { it.id })
        assertEquals(1, DeviceToolCatalog.toolsForMcp("device_get_context").size)
        assertEquals(ToolTransport.WORKSPACE_FILE, DeviceToolCatalog.toolsForMcp("device_get_context").single().transport)
    }

    @Test
    fun `the Device Agent screen's customization list is a subset of the catalog`() {
        for (id in DeviceActionFirewall.CONFIGURABLE_ACTIONS) {
            assertNotNull("$id is offered as a switch but is not a tool", DeviceToolCatalog.tool(id))
        }
        assertTrue(DeviceActionFirewall.CONFIGURABLE_ACTIONS.isNotEmpty())
    }

    @Test
    fun `an unknown id has no declaration rather than an invented one`() {
        assertTrue(DeviceToolCatalog.tool("hack_the_planet") == null)
        assertTrue(DeviceToolCatalog.confirmationFor("hack_the_planet") == null)
        assertTrue(DeviceToolCatalog.riskFor("hack_the_planet") == null)
        assertTrue(DeviceToolCatalog.requirementsFor("hack_the_planet").isEmpty())
        assertEquals(ConfirmationLevel.AUTO, DeviceActionFirewall().levelFor("hack_the_planet"))
    }
}
