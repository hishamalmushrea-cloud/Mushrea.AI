package com.mushrea.code.runtime.local

/**
 * Appended to package-installation failures, where the log alone cannot say whose fault it was.
 *
 * A user on a slow or flaky connection reads `1 error; 1435.7 MiB in 257 packages` as a broken
 * build, deletes the app and moves on, when retrying on a stable network very often succeeds
 * (issue #290). The installers share one wording so the advice reads the same whichever agent's
 * card showed the failure.
 *
 * A missing bundled library (`CANNOT LINK EXECUTABLE` / `libtalloc.so not found`) is the opposite
 * case: retrying the network will not help, and claiming it is a timeout sends the user the wrong
 * way.
 */
internal const val PACKAGE_INSTALL_RETRY_HINT =
    "This is often just a network timeout - retrying the installation on a stable connection usually succeeds."

internal const val PACKAGE_INSTALL_LINKER_HINT =
    "The embedded Linux launcher could not start because a bundled library is missing or not loadable. This is not a network timeout."

internal fun isEmbeddedLinkerFailure(log: String): Boolean {
    val lower = log.lowercase()
    return "cannot link executable" in lower ||
        "not found: needed by" in lower ||
        "library \"libtalloc" in lower ||
        "library \"libandroid-shmem" in lower
}

internal fun packageInstallHintForLog(log: String): String =
    if (isEmbeddedLinkerFailure(log)) PACKAGE_INSTALL_LINKER_HINT else PACKAGE_INSTALL_RETRY_HINT

internal fun packageInstallFailureMessage(
    headline: String,
    log: String,
): String = "$headline ${packageInstallHintForLog(log)}\n\nLast log lines:\n${log.takeLast(4000)}"
