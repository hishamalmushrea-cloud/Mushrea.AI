package com.mushrea.code.device.bridge

/**
 * The three service types adb's wireless debugging publishes (AOSP `adb_wifi.md`).
 *
 * The device publishes all of them; the host only consumes. They are listed in the order the pairing
 * flow meets them.
 */
enum class PeerAdbServiceType(
    /** The DNS-SD type without its trailing dot, as `NsdManager` wants it. */
    val dnsType: String,
) {
    /** Advertised while the pairing server is up, i.e. while the other phone's screen is open. */
    PAIRING("_adb-tls-pairing._tcp"),

    /** Advertised by the TLS server while wireless debugging is switched on. */
    CONNECT("_adb-tls-connect._tcp"),

    /** The legacy `adb tcpip` socket - unencrypted, kept as a fallback only. */
    LEGACY("_adb._tcp"),
    ;

    /** The suffix adb appends to the instance name when it names the device. */
    val adbSerialSuffix: String get() = ".$dnsType"
}

/** One announced service, resolved to an address. */
data class PeerAdbService(
    val type: PeerAdbServiceType,
    val instanceName: String,
    val host: String,
    val port: Int,
) {
    /**
     * The serial adb will use for this service.
     *
     * adb names a wireless device `<instance>.<type>` (the docs' own example is
     * `adb-43081FDAS000VS-QXjCrW._adb-tls-connect._tcp`), and that whole string is what `-s` takes.
     * Predicting it matters because it is how a command is aimed at *this* phone later; when the
     * prediction is wrong the session resolves the serial from `adb devices -l` instead.
     */
    val adbSerial: String get() = instanceName + type.adbSerialSuffix

    /** True when the announcement belongs to the session that asked for [requestedName]. */
    fun matches(requestedName: String): Boolean = instanceName.equals(requestedName, ignoreCase = true)
}

/** One line of `adb devices -l`. */
data class AdbDeviceLine(
    val serial: String,
    val state: String,
    val props: Map<String, String>,
) {
    val usable: Boolean get() = state == "device"
}

/**
 * Reads adb's own output.
 *
 * These are the two places where the platform has to parse text written for humans, so both live in
 * one small object that unit tests can pin: a change in adb's wording then breaks a test with the
 * real string in it instead of breaking a user's pairing flow.
 */
object AdbOutputParser {
    private val DEVICE_LINE = Regex("^(\\S+)\\s+(\\S+)(.*)$")

    /** Parses `adb devices -l`, skipping the header and the trailing blank line. */
    fun devices(output: String): List<AdbDeviceLine> =
        output
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line -> DEVICE_LINE.find(line)?.let { match -> match.toDeviceLine() } }
            .toList()

    private fun MatchResult.toDeviceLine(): AdbDeviceLine {
        val props =
            groupValues[3]
                .trim()
                .split(" ")
                .mapNotNull { token -> token.indexOf(':').takeIf { it > 0 }?.let { token.substring(0, it) to token.substring(it + 1) } }
                .toMap()
        return AdbDeviceLine(serial = groupValues[1], state = groupValues[2], props = props)
    }

    /** True when adb reported a successful pairing in [output]. */
    fun pairedSuccessfully(output: String): Boolean = output.contains("Successfully paired", ignoreCase = true)

    /** True when adb reported a successful connection in [output]. */
    fun connected(output: String): Boolean =
        output.contains("connected to", ignoreCase = true) || output.contains("already connected", ignoreCase = true)

    /**
     * True when an announcement belongs to a device we already know.
     *
     * The serial adb uses *is* the instance name with the service type appended
     * (`adb-XXXX-YY._adb-tls-connect._tcp`), so the known serial starts with the announced instance
     * name. An announcement with no instance name (the legacy socket) is matched by address instead.
     */
    fun serialMatches(
        known: PeerDevice,
        service: PeerAdbService,
    ): Boolean {
        if (service.instanceName.isNotBlank() && known.serial.startsWith(service.instanceName)) return true
        return known.instanceName.isNotBlank() && service.instanceName.equals(known.instanceName, ignoreCase = true)
    }

    /**
     * Picks the serial of the device we just connected.
     *
     * [preferredInstance] is the announcement this session paired with, so its predicted serial is
     * tried first; otherwise the one serial that was not present before the connect is used. Returns
     * null when the answer is not unique - guessing here means aiming the next command at the wrong
     * phone, and a wrong device is worse than an honest "could not identify".
     */
    fun resolveSerial(
        devices: List<AdbDeviceLine>,
        knownSerials: Set<String>,
        preferredInstance: String?,
    ): String? {
        if (preferredInstance != null) {
            devices.firstOrNull { it.serial.startsWith(preferredInstance) }?.let { return it.serial }
        }
        val candidates = devices.filter { it.usable && it.serial !in knownSerials }
        return candidates.singleOrNull()?.serial
    }
}
