package com.mushrea.code.device.bridge

/**
 * Builds the `adb` command lines the peer bridge runs inside the on-device runtime.
 *
 * Every line the app runs is assembled here for one reason: the serial and the arguments come from a
 * device announcement and from an agent, and a command string that is concatenated anywhere else is a
 * command-injection hole (a serial is not always benign - the legacy TCP transport makes it
 * `host:port`, and a hostile mDNS announcement can put anything in an instance name). So:
 *
 *  * **arguments are single-quoted**, with the POSIX `'\''` escape for an embedded quote, so a
 *    payload can never become a second command;
 *  * **the serial is filtered** to the characters adb itself uses, because `-s` also accepts a
 *    device selector prefix and a mangled one must fail loudly rather than select another phone;
 *  * **`-s` is always written when a serial is known**, which is what makes a command target a
 *    specific peer instead of "the first device" - with the runtime's own device in the list, an
 *    unqualified command is how the wrong phone gets modified.
 */
object AdbCommandLine {
    private val SERIAL_ALLOWED = Regex("[^A-Za-z0-9._:-]")

    /** Sanitises a serial for `-s`; a serial that changes under sanitising is rejected. */
    fun serial(raw: String): String {
        val cleaned = raw.trim()
        require(cleaned.isNotEmpty()) { "a device serial is required for every adb command" }
        require(!SERIAL_ALLOWED.containsMatchIn(cleaned)) { "invalid device serial: '$raw'" }
        return cleaned
    }

    /** `'text'` with the POSIX escape for an embedded single quote. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** `adb [-s serial] <parts…>` with every part quoted. */
    fun adb(
        serial: String?,
        vararg parts: String,
    ): String =
        buildString {
            append("adb")
            serial?.let { append(" -s ").append(quote(serial(it))) }
            parts.forEach { append(' ').append(quote(it)) }
        }

    /**
     * `adb [-s serial] shell '<script>'`.
     *
     * The script is quoted as one argument, so the *local* shell inside the runtime cannot expand its
     * `$` variables - they must be expanded on the other phone, by its own shell.
     */
    fun shell(
        serial: String?,
        script: String,
    ): String = adb(serial, "shell", script)

    /** `adb [-s serial] exec-out '<command>'` - binary-clean output, no PTY line mangling. */
    fun execOut(
        serial: String?,
        command: String,
    ): String = adb(serial, "exec-out", command)

    /** `adb pair <host:port> <code>`; the code is quoted like any other argument. */
    fun pair(
        host: String,
        port: Int,
        code: String,
    ): String = adb(null, "pair", "$host:$port", code)

    /** `adb connect <host:port>`. */
    fun connect(
        host: String,
        port: Int,
    ): String = adb(null, "connect", "$host:$port")

    /** `adb disconnect <host:port>`. */
    fun disconnect(
        host: String,
        port: Int,
    ): String = adb(null, "disconnect", "$host:$port")

    /** `adb devices -l`. */
    fun devices(): String = "adb devices -l"
}
