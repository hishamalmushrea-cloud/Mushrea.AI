package com.mushrea.code.core.execution

/**
 * Quoting one shell *argument*.
 *
 * Every command a recipe builds goes through here, because recipe parameters come from an agent and
 * a path like `/sdcard/a; rm -rf /sdcard/b` must arrive at the other phone as one file name, not as
 * two commands. Single quotes are the POSIX answer, with `'\''` for an embedded quote.
 *
 * This lives in `core` so recipes can use it without `core` learning about ADB; the ADB command
 * builder ([com.mushrea.code.device.bridge.AdbCommandLine]) delegates here, so there is exactly one
 * implementation of "how an argument is quoted".
 */
object ShellLine {
    /** `'text'` with the POSIX escape for an embedded single quote. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** A whole line from a program and its arguments, each quoted. */
    fun of(vararg parts: String): String = parts.joinToString(" ") { part -> quote(part) }

    /**
     * A sequence of numbers (`input tap 100 200`).
     *
     * Numeric parameters must not be quoted into a string the device would reject, and must not be
     * free text: an agent that sends `100; rm -rf /sdcard` gets a refusal, not a command.
     */
    fun numbers(
        vararg values: String,
    ): String =
        values.joinToString(" ") { value ->
            val trimmed = value.trim()
            require(trimmed.isNotEmpty() && trimmed.toLongOrNull() != null) { "'$value' must be a number" }
            trimmed
        }

    /** A positive integer with a default; used by the `lines`-style parameters. */
    fun count(
        value: String,
        name: String,
        default: Int,
    ): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return default.toString()
        val parsed = trimmed.toIntOrNull()
        require(parsed != null && parsed > 0 && parsed <= MAX_COUNT) { "$name must be a positive number up to $MAX_COUNT" }
        return parsed.toString()
    }

    private const val MAX_COUNT = 100_000
}
