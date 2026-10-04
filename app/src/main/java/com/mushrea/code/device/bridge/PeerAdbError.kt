package com.mushrea.code.device.bridge

/**
 * Why a peer-device operation failed, in the vocabulary the user and the agent can act on.
 *
 * "Connection failed" is the reason this enum exists. Every branch below has a *different* next
 * step: a missing pairing service means the screen on the other phone is not open, an invalid code
 * means the six digits expired, an auth failure means the host key is no longer trusted. Collapsing
 * them into one message is what makes a wireless-debugging flow feel broken when it is only stale.
 */
enum class PeerAdbErrorCode {
    /** No `_adb-tls-pairing._tcp` announcement matched this session's instance name. */
    PAIRING_SERVICE_NOT_FOUND,

    /** The pairing code was rejected (wrong digits, or the dialog was left too long). */
    PAIRING_CODE_INVALID,

    /** The scanned text was not an ADB pairing QR payload. */
    QR_INVALID,

    /** The pairing handshake itself failed (TLS/SPAKE2 or a protocol fault). */
    PAIRING_FAILED,

    /** Paired, but no `_adb-tls-connect._tcp` announcement appeared. */
    CONNECT_SERVICE_NOT_FOUND,

    /** The device knows this key but refused it - usually a revoked authorization. */
    ADB_AUTH_FAILED,

    /** `adb connect` did not reach a usable device. */
    ADB_CONNECT_FAILED,

    /** A device is listed but not usable (`offline`, `unauthorized`). */
    DEVICE_OFFLINE,

    /** The host or port could not be reached at all. */
    NETWORK_UNREACHABLE,

    /** The other phone's Android is older than 11 (no wireless debugging). */
    UNSUPPORTED_ANDROID,

    /** The app lacks a permission this path needs. */
    PERMISSION_REQUIRED,

    /** The on-device runtime (and therefore `adb`) is not installed or not running. */
    RUNTIME_UNAVAILABLE,

    /** The command ran and failed - nothing was wrong with the channel. */
    COMMAND_FAILED,

    /** A failure none of the above explains; the raw text travels in the detail. */
    UNKNOWN_FAILURE,
}

/** One classified failure: the code, the raw sentence, and what to do next. */
data class PeerAdbError(
    val code: PeerAdbErrorCode,
    val detail: String,
    val nextStep: String,
)

/**
 * Turns `adb`'s output into a [PeerAdbError].
 *
 * The patterns are the ones adb and adbd actually print (client errors on stderr, device-side
 * refusals in the command output). A classifier that cannot match anything says
 * [PeerAdbErrorCode.UNKNOWN_FAILURE] rather than guessing - a wrong next step is worse than a vague
 * one, because it sends the user to the wrong screen.
 */
object PeerAdbErrorClassifier {
    /**
     * The patterns adb and adbd actually print, most specific first.
     *
     * A table rather than a chain of conditions, for one reason: the order *is* the meaning here
     * ("incorrect code" must be tested before the generic pairing failure), and a table makes that
     * order visible and testable instead of implied by control flow.
     */
    private val MARKERS: List<Pair<List<String>, PeerAdbErrorCode>> =
        listOf(
            listOf("incorrect", "wrong password", "invalid code") to PeerAdbErrorCode.PAIRING_CODE_INVALID,
            listOf("failed to authenticate", "protocol fault") to PeerAdbErrorCode.PAIRING_FAILED,
            listOf("unauthorized", "auth reject", "authentication failed", "key rejected") to PeerAdbErrorCode.ADB_AUTH_FAILED,
            listOf("device offline", "offline") to PeerAdbErrorCode.DEVICE_OFFLINE,
            listOf("no route to host", "network is unreachable", "connection refused") to PeerAdbErrorCode.NETWORK_UNREACHABLE,
            listOf("timed out", "timeout") to PeerAdbErrorCode.NETWORK_UNREACHABLE,
            listOf("unable to connect", "failed to connect", "cannot connect") to PeerAdbErrorCode.ADB_CONNECT_FAILED,
            listOf("adb: not found", "command not found", "no such file") to PeerAdbErrorCode.RUNTIME_UNAVAILABLE,
            listOf("device not found", "no devices") to PeerAdbErrorCode.DEVICE_OFFLINE,
            listOf("requires android", "unsupported") to PeerAdbErrorCode.UNSUPPORTED_ANDROID,
            listOf("permission denied") to PeerAdbErrorCode.PERMISSION_REQUIRED,
        )

    /** Classifies what a failed command printed. */
    fun classify(
        output: String,
        fallback: PeerAdbErrorCode = PeerAdbErrorCode.UNKNOWN_FAILURE,
    ): PeerAdbError {
        val text = output.trim()
        val lower = text.lowercase()
        val code = MARKERS.firstOrNull { (markers, _) -> markers.any(lower::contains) }?.second ?: fallback
        return PeerAdbError(code = code, detail = text.ifBlank { code.name }, nextStep = nextStepFor(code))
    }

    /** The instruction a person can follow, keyed by code. */
    fun nextStepFor(code: PeerAdbErrorCode): String =
        when (code) {
            PeerAdbErrorCode.PAIRING_SERVICE_NOT_FOUND ->
                "open Developer options > Wireless debugging on the other phone and start pairing (QR or code); " +
                    "it advertises itself only while that screen is open"
            PeerAdbErrorCode.PAIRING_CODE_INVALID ->
                "the code expires while the dialog is open - read the six digits again and retry"
            PeerAdbErrorCode.QR_INVALID ->
                "scan the QR shown here from Developer options > Wireless debugging > Pair device with QR code"
            PeerAdbErrorCode.PAIRING_FAILED ->
                "close the pairing dialog on the other phone, reopen it, and scan the fresh QR " +
                    "(each session has a new service name and password)"
            PeerAdbErrorCode.CONNECT_SERVICE_NOT_FOUND ->
                "keep Wireless debugging switched on; the connection service appears a moment after pairing"
            PeerAdbErrorCode.ADB_AUTH_FAILED ->
                "the other phone no longer trusts this app key: remove it from Wireless debugging > Paired devices and pair again"
            PeerAdbErrorCode.ADB_CONNECT_FAILED ->
                "check that both phones are on the same Wi-Fi and that the port is the one currently shown on the other phone"
            PeerAdbErrorCode.DEVICE_OFFLINE ->
                "the device is listed but not answering: wake it, keep wireless debugging on, then reconnect"
            PeerAdbErrorCode.NETWORK_UNREACHABLE ->
                "both phones must be on the same network with no client isolation; " +
                    "guest Wi-Fi and VPNs usually block this"
            PeerAdbErrorCode.UNSUPPORTED_ANDROID ->
                "wireless debugging needs Android 11 or newer on the other phone"
            PeerAdbErrorCode.PERMISSION_REQUIRED ->
                "grant the permission the app asked for, then retry"
            PeerAdbErrorCode.RUNTIME_UNAVAILABLE ->
                "install the on-device runtime (it provides the adb client), then retry"
            PeerAdbErrorCode.COMMAND_FAILED ->
                "read stderr: the command reached the device and the device refused it"
            PeerAdbErrorCode.UNKNOWN_FAILURE ->
                "retry once; if it persists, read the detail above"
        }
}
