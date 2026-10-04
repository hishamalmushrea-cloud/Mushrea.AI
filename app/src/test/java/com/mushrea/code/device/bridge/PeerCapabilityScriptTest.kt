package com.mushrea.code.device.bridge

import com.mushrea.code.core.execution.CapabilityKind
import com.mushrea.code.core.execution.CapabilityKinds
import com.mushrea.code.core.execution.CapabilityNames
import com.mushrea.code.core.execution.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe that answers "what can this phone actually do?" - and the parser that reads it. */
class PeerCapabilityScriptTest {
    private val sample =
        """
        bin:sh=/system/bin/sh
        bin:toybox=/system/bin/toybox
        bin:cmd=/system/bin/cmd
        bin:pm=/system/bin/pm
        bin:am=/system/bin/am
        bin:dumpsys=/system/bin/dumpsys
        bin:settings=/system/bin/settings
        bin:logcat=/system/bin/logcat
        bin:screencap=/system/bin/screencap
        bin:input=/system/bin/input
        bin:uiautomator=
        bin:su=
        bin:python3=
        bin:bash=/system/bin/bash
        prop:ro.product.model=Pixel 6a
        prop:ro.product.manufacturer=Google
        prop:ro.build.version.release=13
        prop:ro.build.version.sdk=33
        prop:ro.product.cpu.abi=arm64-v8a
        marker=probe-done
        """.trimIndent()

    @Test
    fun `the script is safe to wrap in one quoted argument`() {
        val script = PeerCapabilityScript.script()

        // [AdbCommandLine.shell] wraps the whole script in one single-quoted argument, so a single
        // quote inside it would end that argument early. Every binary is printed by one loop, which is
        // why the labels are `bin:$b=` and not a literal per binary.
        assertFalse("the probe must not contain a single quote", script.contains("'"))
        assertTrue(script.contains("bin:\$b="))
        assertTrue(script.contains("command -v"))
        assertTrue(script.contains("prop:ro.product.model="))
        assertTrue(script.contains("sh"))
        assertTrue(script.contains("marker=probe-done"))
    }

    @Test
    fun `a device that answered reports what it has and what it does not`() {
        val report = PeerCapabilityScript.parse(sample)

        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.SHELL))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.PM))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.PACKAGE_MANAGER))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.SYNC))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.BASH))
        assertEquals("python3 was measured absent", CapabilityStatus.MISSING, report.status(CapabilityNames.PYTHON))
        assertEquals("uiautomator was measured absent", CapabilityStatus.MISSING, report.status(CapabilityNames.UI_AUTOMATOR))
        assertEquals("su was measured absent", CapabilityStatus.MISSING, report.status(CapabilityNames.SU))
        assertEquals("the path is the detail", "/system/bin/pm", report.detail(CapabilityNames.PM))
    }

    @Test
    fun `a device whose shell never answered proves nothing`() {
        val report = PeerCapabilityScript.parse("")

        // Unknown, not missing: the platform must not report a phone as incapable because the probe
        // never reached it.
        assertEquals(CapabilityStatus.UNKNOWN, report.status(CapabilityNames.SHELL))
        assertTrue(report.names.isEmpty())
    }

    @Test
    fun `the probe looks far past a handful of programs, in one round trip`() {
        val script = PeerCapabilityScript.script()

        // The reported set has to be discoverable, not assumed: interpreters, archive/encryption tools,
        // network tools and the Android entry points are all looked up by the same loop.
        listOf("python3", "perl", "sqlite3", "openssl", "unzip", "wget", "cmd", "uiautomator", "run-as").forEach { program ->
            assertTrue("the probe must look for $program", script.contains(program))
        }
        assertTrue(PeerCapabilityScript.BINARIES.size >= 40)
        // And it measures the facts that decide *how* something can be done, not only which programs exist.
        listOf("fs:sdcard_write", "fs:data_local_tmp_write", "debug:debuggable", "debug:tls_port", "pkg:pm_list", "priv:id")
            .forEach { fact -> assertTrue("the probe must measure $fact", script.contains(fact)) }
        listOf("toybox 2>&1", "cmd -l 2>&1", "service list 2>&1").forEach { list ->
            assertTrue("the probe must read the $list list", script.contains(list))
        }
    }

    @Test
    fun `applets, cmd services and system services become capabilities`() {
        val report =
            PeerCapabilityScript.parse(
                """
                bin:sh=/system/bin/sh
                bin:toybox=/system/bin/toybox
                bin:cmd=/system/bin/cmd
                bin:service=/system/bin/service
                bin:python3=/system/bin/python3
                applet-list-start
                ls cat rm grep sed awk something-unknown
                applet-list-end
                cmd-list-start
                package
                wifi
                cmd-list-end
                svc-list-start
                0 package: [android.content.pm.IPackageManager]
                1 activity: [android.app.IActivityManager]
                svc-list-end
                marker=probe-done
                """.trimIndent(),
            )

        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.applet("ls")))
        assertEquals(CapabilityStatus.UNKNOWN, report.status(CapabilityNames.applet("something-unknown")))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.cmdService("package")))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.cmdService("wifi")))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.service("package")))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.service("activity")))
        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.interpreter("python3")))
        assertEquals("/system/bin/python3", report.detail(CapabilityNames.interpreter("python3")))
        assertEquals(CapabilityKind.APPLET, report.all.first { it.name == CapabilityNames.applet("ls") }.kind)
    }

    @Test
    fun `a list is not read when the program that prints it is absent`() {
        // `cmd -l` on a build without `cmd` prints an error, not services: the guard is what keeps that
        // error text from becoming a capability list.
        val report =
            PeerCapabilityScript.parse(
                """
                bin:sh=/system/bin/sh
                bin:cmd=
                cmd-list-start
                cmd: not found
                cmd-list-end
                marker=probe-done
                """.trimIndent(),
            )

        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.binary("cmd")))
        assertTrue("no service may come out of a missing program", report.names.none { it.startsWith("cmd:") })
    }

    @Test
    fun `filesystem, privilege, package and debugging facts are measured rather than assumed`() {
        val report =
            PeerCapabilityScript.parse(
                """
                bin:sh=/system/bin/sh
                bin:pm=/system/bin/pm
                fs:sdcard_write=yes
                fs:data_local_tmp_write=
                priv:id=uid=2000(shell) gid=2000(shell)
                priv:su=
                pkg:pm_list=yes
                pkg:cmd_package=
                debug:debuggable=1
                debug:secure=1
                build:fingerprint=google/panther/panther:13
                marker=probe-done
                """.trimIndent(),
            )

        assertEquals(CapabilityStatus.AVAILABLE, report.status("fs:sdcard_write"))
        assertEquals(CapabilityStatus.MISSING, report.status("fs:data_local_tmp_write"))
        assertEquals(CapabilityStatus.AVAILABLE, report.status("priv:id"))
        assertEquals(CapabilityStatus.MISSING, report.status("priv:su"))
        assertEquals(CapabilityStatus.AVAILABLE, report.status("pkg:pm_list"))
        assertEquals(CapabilityStatus.MISSING, report.status("pkg:cmd_package"))
        assertEquals(CapabilityStatus.AVAILABLE, report.status("debug:debuggable"))
        assertEquals("google/panther/panther:13", report.detail("build:fingerprint"))
        assertEquals(CapabilityKind.FILESYSTEM, CapabilityKinds.of("fs:sdcard_write"))
        assertEquals(CapabilityKind.PRIVILEGE, CapabilityKinds.of("priv:id"))
        assertEquals(CapabilityKind.DEBUGGING, CapabilityKinds.of("debug:debuggable"))
        assertEquals(CapabilityKind.PLATFORM, CapabilityKinds.of("build:fingerprint"))
    }

    @Test
    fun `the network switches are read from the phone's own settings, without a side effect`() {
        val script = PeerCapabilityScript.script()

        // The device-side command substitution is asserted by its parts: spelling the whole line in a
        // Kotlin string would mean escaping a dollar sign, which is how a test ends up asserting nothing.
        assertTrue("Wi-Fi state is read", script.contains("net:wifi=") && script.contains("settings get global wifi_on"))
        assertTrue(
            "the wireless-debugging switch is read",
            script.contains("net:adb_wifi=") && script.contains("settings get global adb_wifi_enabled"),
        )
        assertTrue("airplane mode is read", script.contains("net:airplane=") && script.contains("settings get global airplane_mode_on"))
        assertFalse("a probe must not change the phone", script.contains("settings put"))
        assertFalse("the probe must not contain a single quote", script.contains("'"))
    }

    @Test
    fun `an on switch is available, an off switch is missing, and an unreported one stays unknown`() {
        val report =
            PeerCapabilityScript.parse(
                """
                bin:sh=/system/bin/sh
                net:wifi=1
                net:adb_wifi=0
                net:airplane=null
                marker=probe-done
                """.trimIndent(),
            )

        assertEquals(CapabilityStatus.AVAILABLE, report.status(CapabilityNames.WIFI))
        assertTrue(report.detail(CapabilityNames.WIFI).contains("on"))
        // "off" is a measurement, so it may block a plan; "not reported" is a build that has no such
        // setting; and a line the probe never printed is no answer at all.
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.WIRELESS_DEBUGGING))
        assertTrue(report.detail(CapabilityNames.WIRELESS_DEBUGGING).contains("off"))
        assertEquals(CapabilityStatus.MISSING, report.status(CapabilityNames.AIRPLANE_MODE))
        assertEquals(CapabilityStatus.UNKNOWN, report.status("net:ethernet"))
        assertEquals(CapabilityKind.NETWORK, CapabilityKinds.of(CapabilityNames.WIFI))
        assertTrue("network facts are their own kind", CapabilityKind.NETWORK in report.kinds)
    }

    @Test
    fun `a network switch the command could not read is not reported as off`() {
        val report =
            PeerCapabilityScript.parse(
                """
                bin:sh=/system/bin/sh
                net:wifi=
                marker=probe-done
                """.trimIndent(),
            )

        // `settings` missing, or the command failing, prints nothing: that is "we could not look",
        // which must never be turned into "the phone's Wi-Fi is off".
        assertEquals(CapabilityStatus.UNKNOWN, report.status(CapabilityNames.WIFI))
    }

    @Test
    fun `the identity comes from the same round trip`() {
        val identity = PeerCapabilityScript.identity(sample)

        assertEquals("Pixel 6a", identity.model)
        assertEquals("Google", identity.manufacturer)
        assertEquals("13", identity.androidVersion)
        assertEquals(33, identity.sdk)
        assertEquals("arm64-v8a", identity.abi)
        assertTrue(identity.known)
    }
}
