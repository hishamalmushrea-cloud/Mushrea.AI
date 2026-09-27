package com.mushrea.code.feature.wakeword

/** The toggle must not persist an impossible state that the foreground service immediately clears. */
internal object WakeWordSettingsPolicy {
    fun canEnable(
        microphonePermission: Boolean,
        assistantActive: Boolean,
    ): Boolean = microphonePermission && assistantActive
}
