package com.mushrea.code.device

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.mushrea.code.R

/**
 * Transparent, instant-exit activity so the launcher's long-press STOP shortcut works (shortcut
 * intents must start activities, not receivers). Requests the Device Agent stop and finishes.
 */
class StopAgentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceAgentStore(applicationContext).requestStop()
        Toast.makeText(this, R.string.device_agent_stopped, Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }
}
