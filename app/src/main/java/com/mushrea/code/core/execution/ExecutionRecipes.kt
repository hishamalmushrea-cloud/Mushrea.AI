package com.mushrea.code.core.execution

import com.mushrea.code.core.permission.PermissionRisk

/**
 * The recipes the platform ships with.
 *
 * These are **not** a tool list: each entry is an objective plus the ways to reach it, written against
 * capability names rather than against a device. Nothing here runs; the [ExecutionPlanner] resolves a
 * recipe against what a target reported, and a provider executes the resolved step after the
 * Permission Center allowed it.
 *
 * Adding an objective is adding an entry below (or a recipe a provider contributes through
 * [RecipeRegistry.with]). Adding a *way* to reach one is adding a candidate. Neither touches the
 * planner, the bridge, the tool catalog or the agent.
 */
object ExecutionRecipes {
    // ---- device ------------------------------------------------------------------------------

    private val deviceInfo =
        recipe(
            id = "device.info",
            title = "Device information",
            description = "The phone's properties: model, build, version - everything getprop reports.",
            keywords = listOf("device info", "phone info", "about the phone", "معلومات الجهاز", "مواصفات الهاتف"),
        ) {
            listOf(candidate("getprop", listOf(shell("getprop", bin("getprop")))))
        }

    private val deviceProps =
        recipe(
            id = "device.props",
            title = "Read properties by prefix",
            description = "getprop filtered to a prefix, e.g. ro.build or ro.product.",
            parameters = listOf(param("prefix", "Property prefix, e.g. ro.build", required = false)),
            keywords = listOf("getprop", "property", "خاصية"),
        ) { values ->
            val prefix = values.optional("prefix")
            val command = if (prefix.isBlank()) "getprop" else "getprop ${ShellLine.quote(prefix)}"
            listOf(candidate("getprop-prefix", listOf(shell(command, bin("getprop")))))
        }

    private val deviceSdk =
        recipe(
            id = "device.sdk",
            title = "API level",
            description = "The Android API level the phone reports.",
            keywords = listOf("api level", "sdk", "مستوى api"),
        ) {
            listOf(candidate("sdk-prop", listOf(shell("getprop ro.build.version.sdk", bin("getprop")))))
        }

    // ---- packages and apps -------------------------------------------------------------------

    private val packagesList =
        recipe(
            id = "packages.list",
            title = "Installed packages",
            description = "Every package installed on the phone, one per line.",
            keywords = listOf("installed apps", "installed packages", "list apps", "التطبيقات المثبتة", "التطبيقات"),
        ) {
            listOf(
                candidate("pm-list", listOf(shell("pm list packages", bin("pm")))),
                candidate("cmd-package-list", listOf(shell("cmd package list packages", bin("cmd")))),
            )
        }

    private val packagesInfo =
        recipe(
            id = "packages.info",
            title = "Package details",
            description = "Version, permissions, activities and services of one package.",
            parameters = listOf(param("package", "Package name, e.g. com.android.settings")),
            keywords = listOf("package info", "app details", "تفاصيل التطبيق", "معلومات الحزمة"),
        ) { values ->
            val target = pkg(values, "package")
            listOf(
                candidate("dumpsys-package", listOf(shell("dumpsys package $target", bin("dumpsys")))),
                candidate("pm-dump", listOf(shell("pm dump $target", bin("pm")))),
            )
        }

    private val packagesPath =
        recipe(
            id = "packages.path",
            title = "Package APK path",
            description = "Where the APK of one package lives on the phone.",
            parameters = listOf(param("package", "Package name")),
            keywords = listOf("apk path", "package path", "مسار التطبيق"),
        ) { values ->
            listOf(candidate("pm-path", listOf(shell("pm path ${pkg(values, "package")}", bin("pm")))))
        }

    private val appStart =
        recipe(
            id = "app.start",
            title = "Start an app",
            description = "Launch a package's main activity, or a component the caller names.",
            parameters = listOf(
                param("package", "Package name, e.g. com.android.settings"),
                param("component", "Explicit component, e.g. com.android.settings/.Settings", required = false),
            ),
            keywords = listOf("open app", "start app", "launch app", "افتح التطبيق", "شغّل التطبيق"),
        ) { values ->
            val packageName = pkg(values, "package")
            val component = values.optional("component")
            val candidates = mutableListOf<RecipeCandidate>()
            if (component.isNotBlank()) {
                candidates +=
                    candidate("am-start-component", listOf(mutating("am start -n ${componentName(component)}", bin("am"))))
            }
            candidates +=
                candidate(
                    "monkey-launcher",
                    listOf(mutating("monkey -p $packageName -c android.intent.category.LAUNCHER 1", bin("monkey"))),
                    note = "launch the package's launcher activity through monkey",
                )
            candidates
        }

    private val appStop =
        recipe(
            id = "app.stop",
            title = "Stop an app",
            description = "Force-stop a package.",
            parameters = listOf(param("package", "Package name")),
            keywords = listOf("stop app", "force stop", "أوقف التطبيق"),
        ) { values ->
            listOf(candidate("am-force-stop", listOf(mutating("am force-stop ${pkg(values, "package")}", bin("am")))))
        }

    private val appInstall =
        recipe(
            id = "app.install",
            title = "Install an APK",
            description = "Install (or upgrade with -r) an APK that is already on this phone.",
            parameters = listOf(
                param("apk", "Path of the APK on this phone", example = "/sdcard/Download/app.apk"),
                param("remote", "Path to stage it at on the other phone", required = false, default = STAGING_APK),
            ),
            keywords = listOf("install apk", "install app", "ثبّت التطبيق", "تثبيت apk"),
        ) { values ->
            val apk = path(values, "apk")
            val remote = path(values, "remote", STAGING_APK)
            listOf(
                candidate(
                    "adb-install",
                    listOf(
                        RecipeAction(
                            operation = ExecutionOperation.INSTALL,
                            invocation = transfer(apk, remote),
                            effect = DESTRUCTIVE_EFFECT,
                            capability = CapabilityNames.INSTALL,
                            description = "adb install -r",
                        ),
                    ),
                ),
                candidate(
                    "push-then-pm-install",
                    listOf(
                        push(apk, remote, CapabilityNames.SYNC),
                        mutating("pm install -r ${ShellLine.quote(remote)}", bin("pm")),
                    ),
                    requires = listOf(req(CapabilityNames.SYNC), req(bin("pm"))),
                    note = "stage the APK on the phone, then let its own package manager install it",
                ),
            )
        }

    private val appUninstall =
        recipe(
            id = "app.uninstall",
            title = "Uninstall an app",
            description = "Remove a package from the other phone. This deletes the app's data.",
            parameters = listOf(param("package", "Package name")),
            keywords = listOf("uninstall", "remove app", "احذف التطبيق", "إزالة التطبيق"),
        ) { values ->
            val target = pkg(values, "package")
            listOf(
                candidate("pm-uninstall", listOf(destructive("pm uninstall $target", bin("pm")))),
                candidate("cmd-package-uninstall", listOf(destructive("cmd package uninstall $target", bin("cmd")))),
            )
        }

    // ---- files -------------------------------------------------------------------------------

    private val filesList =
        recipe(
            id = "files.list",
            title = "List a directory",
            description = "List files on the other phone, hidden ones included.",
            parameters = listOf(param("path", "Directory on the other phone", required = false, default = "/sdcard")),
            keywords = listOf("list files", "list directory", "اعرض الملفات"),
        ) { values ->
            val target = path(values, "path", "/sdcard")
            listOf(
                candidate(
                    "ls",
                    listOf(shell("ls -la ${ShellLine.quote(target)}", bin("ls"))),
                    requires = listOf(any(bin("ls"), applet("ls"))),
                ),
                candidate("toybox-ls", listOf(shell("toybox ls -la ${ShellLine.quote(target)}", bin("toybox")))),
            )
        }

    private val filesRead =
        recipe(
            id = "files.read",
            title = "Read a file",
            description = "Print a text file from the other phone.",
            parameters = listOf(param("path", "File path on the other phone")),
            keywords = listOf("read file", "show file", "اقرأ الملف"),
        ) { values ->
            val target = path(values, "path")
            listOf(
                candidate(
                    "cat",
                    listOf(shell("cat ${ShellLine.quote(target)}", bin("cat"))),
                    requires = listOf(any(bin("cat"), applet("cat"))),
                ),
                candidate("toybox-cat", listOf(shell("toybox cat ${ShellLine.quote(target)}", bin("toybox")))),
            )
        }

    private val filesExists =
        recipe(
            id = "files.exists",
            title = "Check a path",
            description = "Whether a path exists on the other phone (and its size, when it does).",
            parameters = listOf(param("path", "Path on the other phone")),
            keywords = listOf("file exists", "does the file exist", "هل يوجد الملف"),
        ) { values ->
            listOf(
                candidate(
                    "ls-path",
                    listOf(shell("ls -la ${ShellLine.quote(path(values, "path"))}", bin("ls"))),
                    requires = listOf(any(bin("ls"), applet("ls"))),
                ),
            )
        }

    private val filesPush =
        recipe(
            id = "files.push",
            title = "Copy a file to the phone",
            description = "Send a file from this phone to a path on the other phone.",
            parameters = listOf(
                param("local", "Path on this phone"),
                param("remote", "Path on the other phone", required = false, default = STAGING_DIR),
            ),
            keywords = listOf("push file", "copy to phone", "ارسل ملف", "انسخ ملف"),
        ) { values ->
            listOf(candidate("adb-push", listOf(push(path(values, "local"), path(values, "remote", STAGING_DIR), CapabilityNames.SYNC))))
        }

    private val filesPull =
        recipe(
            id = "files.pull",
            title = "Copy a file from the phone",
            description = "Bring a file from the other phone to this one.",
            parameters = listOf(
                param("remote", "Path on the other phone"),
                param("local", "Path on this phone"),
            ),
            keywords = listOf("pull file", "copy from phone", "اسحب ملف", "انسخ من الهاتف"),
        ) { values ->
            listOf(candidate("adb-pull", listOf(pull(path(values, "remote"), path(values, "local")))))
        }

    private val filesMkdir =
        recipe(
            id = "files.mkdir",
            title = "Create a directory",
            description = "mkdir -p on the other phone.",
            parameters = listOf(param("path", "Directory to create")),
            keywords = listOf("create directory", "mkdir", "أنشئ مجلد"),
        ) { values ->
            listOf(
                candidate(
                    "mkdir-p",
                    listOf(
                        mutating(
                            "mkdir -p ${ShellLine.quote(path(values, "path"))}",
                            bin("mkdir"),
                            verify = "ls -d ${ShellLine.quote(path(values, "path"))}",
                        ),
                    ),
                    requires = listOf(any(bin("mkdir"), applet("mkdir"))),
                ),
            )
        }

    private val filesRemove =
        recipe(
            id = "files.remove",
            title = "Delete a file",
            description = "Delete a path on the other phone. This cannot be undone.",
            parameters = listOf(param("path", "Path to delete")),
            keywords = listOf("delete file", "remove file", "احذف الملف"),
        ) { values ->
            listOf(
                candidate(
                    "rm-f",
                    listOf(destructive("rm -f ${ShellLine.quote(path(values, "path"))}", bin("rm"))),
                    requires = listOf(any(bin("rm"), applet("rm"))),
                ),
            )
        }

    // ---- screen and input --------------------------------------------------------------------

    private val screenCapture =
        recipe(
            id = "screen.capture",
            title = "Take a screenshot",
            description = "Capture the other phone's screen into a file on this phone.",
            parameters = listOf(
                param("local", "Where to save the PNG on this phone"),
                param("remote", "Where to write it on the other phone first", required = false, default = DEFAULT_SCREENSHOT),
            ),
            keywords = listOf("screenshot", "screen capture", "خذ لقطة", "صورة الشاشة", "screencap"),
        ) { values ->
            val remote = path(values, "remote", DEFAULT_SCREENSHOT)
            val local = path(values, "local")
            listOf(
                candidate(
                    "screencap-then-pull",
                    listOf(
                        mutating(
                            "screencap -p ${ShellLine.quote(remote)}",
                            bin("screencap"),
                            verify = "ls -l ${ShellLine.quote(remote)}",
                        ),
                        pull(remote, local),
                    ),
                    note = "capture on the phone, then pull the PNG",
                ),
            )
        }

    private val screenSize =
        recipe(
            id = "screen.size",
            title = "Screen resolution",
            description = "The phone's screen size (and its override, when one is set).",
            keywords = listOf("screen size", "resolution", "دقة الشاشة"),
        ) {
            listOf(candidate("wm-size", listOf(shell("wm size", bin("wm")))))
        }

    private val screenDensity =
        recipe(
            id = "screen.density",
            title = "Screen density",
            description = "The phone's display density.",
            keywords = listOf("screen density", "dpi", "كثافة الشاشة"),
        ) {
            listOf(candidate("wm-density", listOf(shell("wm density", bin("wm")))))
        }

    private val inputTap =
        recipe(
            id = "input.tap",
            title = "Tap a point",
            description = "Tap at screen coordinates on the other phone.",
            parameters = listOf(param("x", "X coordinate"), param("y", "Y coordinate")),
            keywords = listOf("tap", "touch", "اضغط على"),
        ) { values ->
            val point = ShellLine.numbers(values.required("x"), values.required("y"))
            listOf(candidate("input-tap", listOf(mutating("input tap $point", bin("input")))))
        }

    private val inputSwipe =
        recipe(
            id = "input.swipe",
            title = "Swipe",
            description = "Swipe between two points on the other phone.",
            parameters = listOf(
                param("x1", "Start X"),
                param("y1", "Start Y"),
                param("x2", "End X"),
                param("y2", "End Y"),
                param("duration_ms", "Duration in milliseconds", required = false, default = "300"),
            ),
            keywords = listOf("swipe", "scroll", "مرّر"),
        ) { values ->
            val points =
                ShellLine.numbers(
                    values.required("x1"),
                    values.required("y1"),
                    values.required("x2"),
                    values.required("y2"),
                )
            val duration = ShellLine.count(values["duration_ms"].orEmpty(), "duration_ms", 300)
            listOf(candidate("input-swipe", listOf(mutating("input swipe $points $duration", bin("input")))))
        }

    private val inputText =
        recipe(
            id = "input.text",
            title = "Type text",
            description = "Type into the focused field on the other phone.",
            parameters = listOf(param("text", "The text to type")),
            keywords = listOf("type text", "write text", "اكتب نص"),
        ) { values ->
            // `input text` reads a space as a word break on some builds; `%s` is the accepted
            // placeholder, and the whole argument stays quoted so nothing else is interpreted.
            val text = values.required("text").replace(" ", "%s")
            listOf(candidate("input-text", listOf(mutating("input text ${ShellLine.quote(text)}", bin("input")))))
        }

    private val inputKey =
        recipe(
            id = "input.key",
            title = "Press a key",
            description = "Send a key event, e.g. KEYCODE_HOME or KEYCODE_BACK.",
            parameters = listOf(param("keycode", "Key code name or number", example = "KEYCODE_BACK")),
            keywords = listOf("keyevent", "press key", "اضغط زر"),
        ) { values ->
            listOf(candidate("input-keyevent", listOf(mutating("input keyevent ${keycode(values.required("keycode"))}", bin("input")))))
        }

    private val uiDump =
        recipe(
            id = "ui.dump",
            title = "Dump the UI tree",
            description = "The accessibility node tree of the current screen, as XML.",
            parameters = listOf(param("remote", "Where the phone writes the dump first", required = false, default = DEFAULT_UI_DUMP)),
            keywords = listOf("ui dump", "uiautomator", "screen tree", "شجرة الواجهة"),
        ) { values ->
            val remote = path(values, "remote", DEFAULT_UI_DUMP)
            listOf(
                candidate("uiautomator-tty", listOf(shell("uiautomator dump /dev/tty", bin("uiautomator")))),
                candidate(
                    "uiautomator-then-cat",
                    listOf(
                        mutating("uiautomator dump ${ShellLine.quote(remote)}", bin("uiautomator")),
                        shell("cat ${ShellLine.quote(remote)}", bin("cat")),
                    ),
                    note = "write the dump on the phone, then read it back",
                ),
            )
        }

    // ---- logs, settings, services, processes -------------------------------------------------

    private val logTail =
        recipe(
            id = "log.tail",
            title = "Read the log",
            description = "The most recent logcat lines, without waiting for more.",
            parameters = listOf(param("lines", "How many lines", required = false, default = "200")),
            keywords = listOf("logcat", "logs", "السجل", "سجل النظام"),
        ) { values ->
            val lines = ShellLine.count(values["lines"].orEmpty(), "lines", 200)
            listOf(candidate("logcat-dump", listOf(shell("logcat -d -t $lines", bin("logcat")))))
        }

    private val logClear =
        recipe(
            id = "log.clear",
            title = "Clear the log",
            description = "Empty the phone's log buffer.",
            keywords = listOf("clear log", "logcat -c", "امسح السجل"),
        ) {
            listOf(candidate("logcat-clear", listOf(mutating("logcat -c", bin("logcat")))))
        }

    private val settingsGet =
        recipe(
            id = "settings.get",
            title = "Read a setting",
            description = "One value from the system, secure or global settings table.",
            parameters = listOf(
                param("namespace", "system, secure or global", required = false, default = "system"),
                param("key", "Setting name", example = "screen_brightness"),
            ),
            keywords = listOf("get setting", "اقرأ إعداد"),
        ) { values ->
            val table = namespace(values.optional("namespace"))
            val key = ShellLine.quote(values.required("key"))
            listOf(candidate("settings-get", listOf(shell("settings get $table $key", bin("settings")))))
        }

    private val settingsPut =
        recipe(
            id = "settings.put",
            title = "Change a setting",
            description = "Write one value in the system, secure or global settings table.",
            parameters = listOf(
                param("namespace", "system, secure or global", required = false, default = "system"),
                param("key", "Setting name"),
                param("value", "New value"),
            ),
            keywords = listOf("set setting", "change setting", "غيّر إعداد"),
        ) { values ->
            val table = namespace(values.optional("namespace"))
            val key = ShellLine.quote(values.required("key"))
            val value = ShellLine.quote(values.required("value"))
            listOf(
                candidate(
                    "settings-put",
                    listOf(mutating("settings put $table $key $value", bin("settings"), verify = "settings get $table $key")),
                ),
            )
        }

    private val servicesList =
        recipe(
            id = "services.list",
            title = "System services",
            description = "The Android system services the phone exposes to adb.",
            keywords = listOf("services", "system services", "الخدمات"),
        ) {
            listOf(
                candidate("service-list", listOf(shell("service list", bin("service")))),
                candidate("cmd-l", listOf(shell("cmd -l", bin("cmd")))),
            )
        }

    private val processesList =
        recipe(
            id = "processes.list",
            title = "Running processes",
            description = "The processes running on the phone.",
            keywords = listOf("processes", "ps", "العمليات"),
        ) {
            listOf(
                candidate(
                    "ps-a",
                    listOf(shell("ps -A", bin("ps"))),
                    requires = listOf(any(bin("ps"), applet("ps"))),
                    note = "the applet form is accepted because `ps` is a toybox applet on most builds",
                ),
                candidate("toybox-ps", listOf(shell("toybox ps -A", bin("toybox")))),
            )
        }

    private val batteryStatus =
        recipe(
            id = "battery.status",
            title = "Battery status",
            description = "Charge level, health and power state.",
            keywords = listOf("battery", "البطارية"),
        ) {
            listOf(candidate("dumpsys-battery", listOf(shell("dumpsys battery", bin("dumpsys")))))
        }

    private val diskUsage =
        recipe(
            id = "disk.usage",
            title = "Disk usage",
            description = "Free space per mounted filesystem.",
            keywords = listOf("disk", "storage", "المساحة", "التخزين"),
        ) {
            listOf(
                candidate(
                    "df-h",
                    listOf(shell("df -h", bin("df"))),
                    requires = listOf(any(bin("df"), applet("df"))),
                ),
                candidate("toybox-df", listOf(shell("toybox df -h", bin("toybox")))),
            )
        }

    private val networkInfo =
        recipe(
            id = "network.info",
            title = "Network interfaces",
            description = "The phone's network interfaces and addresses.",
            keywords = listOf("network", "ip address", "wifi info", "الشبكة"),
        ) {
            listOf(
                candidate("ip-addr", listOf(shell("ip addr", bin("ip")))),
                candidate("ifconfig", listOf(shell("ifconfig", bin("ifconfig")))),
            )
        }

    // ---- provisioning: keeping a remote phone reachable --------------------------------------

    private val provisionStayAwake =
        recipe(
            id = "provision.stay_awake",
            title = "Keep the phone awake while charging",
            description = "Stops the sleep policy from cutting a long remote session short.",
            keywords = listOf("stay awake", "keep awake", "prevent sleep", "أبق الهاتف مستيقظا", "منع النوم", "لا تدع الهاتف ينام"),
        ) {
            listOf(
                candidate(
                    "settings-stay-on",
                    // The same write the provisioning host makes as its PERSIST step, reachable here as
                    // an ordinary objective so the agent does not need the provisioning flow for it.
                    listOf(
                        mutating(
                            "settings put global stay_on_while_plugged_in 3",
                            bin("settings"),
                            verify = "settings get global stay_on_while_plugged_in",
                        ),
                    ),
                ),
            )
        }

    private val provisionWifiAlive =
        recipe(
            id = "provision.wifi_alive",
            title = "Keep Wi-Fi on while the phone sleeps",
            description = "Wi-Fi staying up is what keeps a wireless-debugging route reachable while the phone is idle.",
            keywords = listOf("keep wifi", "wifi sleep", "wifi during sleep", "أبق الواي فاي", "الواي فاي أثناء النوم"),
        ) {
            listOf(
                candidate(
                    "settings-wifi-sleep",
                    listOf(
                        mutating(
                            "settings put global wifi_sleep_policy 2",
                            bin("settings"),
                            verify = "settings get global wifi_sleep_policy",
                        ),
                    ),
                ),
            )
        }

    private val provisionWirelessDebugging =
        recipe(
            id = "provision.wireless_debugging",
            title = "Is wireless debugging on?",
            description =
                "Reads the phone's own wireless-debugging switch - the setting whose state decides " +
                    "whether a pairing can exist at all. The platform can read it and cannot turn it " +
                    "on: that switch is the phone owner's, on the other screen.",
            keywords = listOf("wireless debugging", "adb wifi", "adb over wifi", "التصحيح اللاسلكي", "التصحيح اللاسلكي مفعل"),
        ) {
            listOf(candidate("settings-adb-wifi", listOf(shell("settings get global adb_wifi_enabled", bin("settings")))))
        }

    private val provisionAdbPort =
        recipe(
            id = "provision.adb_port",
            title = "Make the ADB port fixed",
            description = "Writes service.adb.tcp.port so the next adbd start listens on a port that does not change.",
            parameters = listOf(param("port", "Port adbd should listen on", required = false, default = "5555", example = "5555")),
            keywords = listOf("fixed adb port", "adb port", "tcpip port", "منفذ adb ثابت", "ثبّت المنفذ"),
        ) { values ->
            val port = portNumber(values.optional("port", "5555"))
            listOf(
                candidate(
                    "setprop-adb-tcp-port",
                    listOf(
                        mutating(
                            "setprop service.adb.tcp.port $port",
                            CapabilityNames.PRIVILEGE_PREFIX + "su",
                            verify = "getprop service.adb.tcp.port",
                        ),
                    ),
                    note =
                        "adbd reads this property when it next starts; restarting adbd from this channel would drop the " +
                            "very connection the plan is using, so the platform sets the property and leaves the restart to the owner",
                ),
            )
        }

    // ---- general execution -------------------------------------------------------------------

    private val scriptRun =
        recipe(
            id = "script.run",
            title = "Run a script",
            description = "Run a script on the other phone through an interpreter it actually has.",
            parameters = listOf(
                param("script", "The script body"),
                param("interpreter", "Interpreter to use", required = false, default = "sh"),
                param(
                    "shell_fallback",
                    "Set to yes to allow a shell-compatible script to fall back to sh",
                    required = false,
                    default = "no",
                ),
            ),
            keywords = listOf("run script", "execute script", "شغّل سكربت", "نفذ سكربت"),
        ) { values ->
            val script = values.required("script")
            val interpreter = values.optional("interpreter", "sh")
            val shellFallback = values.optional("shell_fallback").equals("yes", ignoreCase = true)
            val candidates = mutableListOf<RecipeCandidate>()
            candidates += scriptCandidate(script, interpreter)
            if (shellFallback && interpreter != "sh") {
                candidates +=
                    scriptCandidate(script, "sh").copy(
                        id = "script-with-sh",
                        note = "shell fallback the caller allowed with shell_fallback=yes",
                    )
            }
            candidates
        }

    private val programRun =
        recipe(
            id = "program.run",
            title = "Run a program",
            description = "One program with arguments, passed as an argv rather than a shell line.",
            parameters = listOf(
                param("program", "Program path or name"),
                param("arguments", "Arguments, space separated", required = false),
            ),
            keywords = listOf("run program", "شغّل برنامج"),
        ) { values ->
            val program = values.required("program")
            val arguments = values.optional("arguments").split(' ').filter(String::isNotBlank)
            listOf(
                candidate(
                    "exec-argv",
                    listOf(
                        RecipeAction(
                            operation = ExecutionOperation.EXEC,
                            invocation = ExecutionInvocation(program, arguments),
                            effect = READ_EFFECT,
                            capability = CapabilityNames.EXEC_OUT,
                            description = "exec-out $program",
                        ),
                    ),
                    requires = listOf(CapabilityRequirement.optional(CapabilityNames.EXEC_OUT)),
                ),
            )
        }

    private val shellRun =
        recipe(
            id = "shell.run",
            title = "Run a shell line",
            description = "One shell line on the other phone. Use this when no recipe fits the goal.",
            parameters = listOf(param("command", "The shell line")),
            keywords = listOf("shell", "run command", "نفذ أمر"),
        ) { values ->
            listOf(
                candidate(
                    "shell-line",
                    listOf(shell(values.required("command"), CapabilityNames.SHELL)),
                    requires = listOf(req(CapabilityNames.SHELL)),
                ),
            )
        }

    /**
     * Every recipe, in the order a listing shows them.
     *
     * A provider can add its own with [RecipeRegistry.with]; nothing here needs to know they exist.
     */
    val all: List<ExecutionRecipe> =
        listOf(
            deviceInfo,
            deviceProps,
            deviceSdk,
            packagesList,
            packagesInfo,
            packagesPath,
            appStart,
            appStop,
            appInstall,
            appUninstall,
            filesList,
            filesRead,
            filesExists,
            filesPush,
            filesPull,
            filesMkdir,
            filesRemove,
            screenCapture,
            screenSize,
            screenDensity,
            inputTap,
            inputSwipe,
            inputText,
            inputKey,
            uiDump,
            logTail,
            logClear,
            settingsGet,
            settingsPut,
            servicesList,
            processesList,
            batteryStatus,
            diskUsage,
            networkInfo,
            provisionStayAwake,
            provisionWifiAlive,
            provisionWirelessDebugging,
            provisionAdbPort,
            scriptRun,
            programRun,
            shellRun,
        )

    val registry: RecipeRegistry = RecipeRegistry(all)

    private const val STAGING_DIR = "/data/local/tmp"
    private const val STAGING_APK = "/data/local/tmp/mushrea-install.apk"
    private const val DEFAULT_SCREENSHOT = "/sdcard/mushrea-screen.png"
    private const val DEFAULT_UI_DUMP = "/sdcard/mushrea-ui.xml"
}

// ---- small builders, so each recipe above reads as data ----------------------------------------

private fun recipe(
    id: String,
    title: String,
    description: String,
    parameters: List<RecipeParameter> = emptyList(),
    keywords: List<String> = emptyList(),
    builder: (RecipeValues) -> List<RecipeCandidate>,
): ExecutionRecipe = ExecutionRecipe(id, title, description, parameters, keywords) { values -> builder(RecipeValues(values)) }

/**
 * The parameter map with the conveniences a builder needs: a required value that refuses to be blank,
 * and optional values with defaults. A missing required parameter throws, and the planner turns that
 * into a blocker the agent can fix by re-sending - it never becomes a command with an empty hole.
 */
private class RecipeValues(private val values: Map<String, String>) {
    fun required(name: String): String {
        val value = values[name]?.trim().orEmpty()
        require(value.isNotEmpty()) { "parameter '$name' is required" }
        return value
    }

    operator fun get(name: String): String? = values[name]

    fun optional(
        name: String,
        default: String = "",
    ): String = values[name]?.trim()?.takeIf(String::isNotEmpty) ?: default
}

private fun shell(
    command: String,
    capability: String,
    verify: String = "",
): RecipeAction =
    RecipeAction(ExecutionOperation.SHELL, ExecutionInvocation(command), READ_EFFECT, capability, verify, command)

private fun mutating(
    command: String,
    capability: String,
    verify: String = "",
): RecipeAction =
    RecipeAction(ExecutionOperation.SHELL, ExecutionInvocation(command), WRITE_EFFECT, capability, verify, command)

private fun destructive(
    command: String,
    capability: String,
): RecipeAction =
    RecipeAction(ExecutionOperation.SHELL, ExecutionInvocation(command), DESTRUCTIVE_EFFECT, capability, description = command)

private fun push(
    local: String,
    remote: String,
    capability: String,
): RecipeAction =
    RecipeAction(
        ExecutionOperation.PUSH,
        transfer(local, remote),
        WRITE_EFFECT,
        capability,
        verify = "ls -l ${ShellLine.quote(remote)}",
        description = "push $local",
    )

private fun pull(
    remote: String,
    local: String,
): RecipeAction =
    RecipeAction(ExecutionOperation.PULL, transfer(local, remote), READ_EFFECT, CapabilityNames.SYNC, description = "pull $remote")

private fun transfer(
    local: String,
    remote: String,
): ExecutionInvocation = ExecutionInvocation("", files = listOf(ExecutionFile(localPath = local, remotePath = remote)))

/**
 * One candidate. [requires] is the *capabilities* it needs; when a candidate does not name them, they
 * are derived from the capabilities its actions declare - which is why a candidate that runs `pm`
 * cannot be planned on a phone that reported no `pm`, and why a candidate whose program is a `toybox`
 * applet says so instead of pretending the same command works everywhere.
 */
private fun candidate(
    id: String,
    actions: List<RecipeAction>,
    requires: List<CapabilityRequirement> = emptyList(),
    note: String = "",
): RecipeCandidate =
    RecipeCandidate(
        id = id,
        actions = actions,
        requires =
            requires.ifEmpty {
                actions.mapNotNull { action -> action.capability.takeIf(String::isNotBlank)?.let { name -> req(name) } }
            },
        note = note,
    )

private fun scriptCandidate(
    script: String,
    interpreter: String,
): RecipeCandidate {
    val capability = if (interpreter == "sh") bin("sh") else CapabilityNames.interpreter(interpreter)
    val alternatives = if (interpreter == "sh") listOf(CapabilityNames.SHELL) else listOf(bin(interpreter))
    return candidate(
        id = "script-with-$interpreter",
        actions =
            listOf(
                RecipeAction(
                    operation = ExecutionOperation.SCRIPT,
                    invocation = ExecutionInvocation(script, interpreter = interpreter),
                    effect = WRITE_EFFECT,
                    capability = capability,
                    description = "run the script through $interpreter",
                ),
            ),
        requires = listOf(req(capability, *alternatives.toTypedArray())),
    )
}

private fun param(
    name: String,
    description: String,
    required: Boolean = true,
    default: String = "",
    example: String = "",
): RecipeParameter =
    RecipeParameter(name = name, description = description, required = required, default = default, example = example)

private fun req(
    name: String,
    vararg alternatives: String,
): CapabilityRequirement =
    if (alternatives.isEmpty()) CapabilityRequirement.of(name) else CapabilityRequirement.any(name, *alternatives)

private fun any(vararg names: String): CapabilityRequirement = CapabilityRequirement.any(*names)

/** Program names are namespaced, so a plan says *which* capability a step uses, not just the command. */
private fun bin(program: String): String = CapabilityNames.binary(program)

private fun applet(name: String): String = CapabilityNames.applet(name)

/** A package name is an identifier, never a shell word: it is validated instead of quoted. */
private fun pkg(
    values: RecipeValues,
    name: String,
): String {
    val value = values.required(name)
    require(PACKAGE_PATTERN.matches(value)) { "'$value' is not a package name" }
    return value
}

/** A component is `package/Class` (or `package/.Class`); validated for the same reason. */
private fun componentName(value: String): String {
    require(COMPONENT_PATTERN.matches(value)) { "'$value' is not a component name" }
    return value
}

private fun path(
    values: RecipeValues,
    name: String,
    default: String = "",
): String {
    val value = values.optional(name, default)
    require(value.isNotBlank()) { "parameter '$name' is required" }
    require(!value.contains('\n')) { "a path cannot contain a newline" }
    return value
}

private fun keycode(value: String): String {
    val trimmed = value.trim().uppercase()
    require(KEYCODE_PATTERN.matches(trimmed)) { "'$value' is not a key code" }
    return trimmed
}

private fun namespace(value: String): String {
    val trimmed = value.trim().lowercase().ifBlank { "system" }
    require(trimmed in NAMESPACES) { "namespace must be one of ${NAMESPACES.joinToString()}" }
    return trimmed
}

/**
 * A TCP port the device may listen on.
 *
 * Validated rather than pasted: a parameter that reaches `setprop` unquoted is a command injection,
 * and a port outside the range is a typo that would look like a device failure.
 */
private fun portNumber(value: String): Int {
    val parsed = value.trim().toIntOrNull()
    require(parsed != null && parsed in 1024..65_535) { "port must be a number between 1024 and 65535" }
    return parsed
}

private val READ_EFFECT = ExecutionEffect(mutatesTarget = false)
private val WRITE_EFFECT = ExecutionEffect(mutatesTarget = true, risk = PermissionRisk.MEDIUM)
private val DESTRUCTIVE_EFFECT = ExecutionEffect(mutatesTarget = true, destructive = true, risk = PermissionRisk.HIGH)
private val NAMESPACES = setOf("system", "secure", "global")
private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private val COMPONENT_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.$]+")
private val KEYCODE_PATTERN = Regex("(KEYCODE_[A-Z0-9_]+)|[0-9]{1,4}")
