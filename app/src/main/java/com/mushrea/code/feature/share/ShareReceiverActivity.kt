package com.mushrea.code.feature.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.mushrea.code.MushreaCodeApplication
import com.mushrea.code.MainActivity
import com.mushrea.code.R
import com.mushrea.code.core.api.PromptRequest
import com.mushrea.code.core.locale.AppLanguage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system share/open-with target: text shared from any app becomes an editable prompt for the
 * agent, and a file shared or opened from a file manager is copied into the device's shared
 * storage (Download/Mushrea-imports — inside the storage the runtime can actually read) with a
 * ready prompt pointing at it. Sending creates a fresh session, exactly like the quick-input
 * widget; "open app" just lands the user in the normal UI with the text kept in the field.
 */
class ShareReceiverActivity : ComponentActivity() {
    private var pendingInputField: EditText? = null
    private var statusText: TextView? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (resources.displayMetrics.density * 24).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding * 2, padding, padding)
                setBackgroundColor(0xF5111111.toInt())
            }

        val title =
            TextView(this).apply {
                text = getString(R.string.share_receiver_title)
                textSize = 16f
                setTextColor(0xFFEEEEEE.toInt())
                setPadding(0, 0, 0, 12)
            }
        root.addView(title)

        val status =
            TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFAAAAAA.toInt())
                setPadding(0, 0, 0, 12)
            }
        root.addView(status)
        statusText = status

        val input =
            EditText(this).apply {
                hint = getString(R.string.share_receiver_hint)
                minLines = 3
                gravity = android.view.Gravity.TOP
                setBackgroundColor(0xFF222230.toInt())
                setTextColor(0xFFEEEEEE.toInt())
                setHintTextColor(0xFF777777.toInt())
            }
        root.addView(input)
        pendingInputField = input

        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 0)
            }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.share_send_to_agent)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { sendToAgent(input.text.toString().trim()) }
            },
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.share_open_app)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { openApp() }
            },
        )
        root.addView(buttons)
        setContentView(root)

        when {
            intent?.action == Intent.ACTION_SEND -> handleSend(intent)
            intent?.action == Intent.ACTION_VIEW -> handleView(intent)
        }
    }

    private fun handleSend(intent: Intent) {
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        when {
            stream != null -> importFile(stream)
            !text.isNullOrBlank() -> pendingInputField?.setText(text)
            else -> statusText?.text = getString(R.string.share_nothing_to_import)
        }
    }

    private fun handleView(intent: Intent) {
        intent.data?.let { importFile(it) }
            ?: run { statusText?.text = getString(R.string.share_nothing_to_import) }
    }

    /**
     * Copies the shared file into Download/Mushrea-imports on the device's shared storage — the
     * same area the runtime's /sdcard binding can read, so the agent can genuinely open it after
     * the user sends the pre-filled prompt.
     */
    private fun importFile(uri: Uri) {
        val status = statusText ?: return
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val name = resolveDisplayName(uri) ?: "file"
                        val dir = File(android.os.Environment.getExternalStorageDirectory(), "Download/Mushrea-imports")
                        if (!dir.isDirectory && !dir.mkdirs()) error("cannot create ${dir.absolutePath}")
                        val destination = File(dir, System.currentTimeMillis().toString() + "-" + name)
                        contentResolver.openInputStream(uri)?.use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("cannot open the shared file")
                        destination
                    }
                }
            result.onSuccess { file ->
                status.text = getString(R.string.share_import_saved, file.absolutePath)
                status.setTextColor(0xFF55FF55.toInt())
                pendingInputField?.setText(getString(R.string.share_prompt_with_file, file.absolutePath))
            }.onFailure { error ->
                status.text = getString(R.string.share_import_failed, error.message ?: "?")
                status.setTextColor(0xFFFF5555.toInt())
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    /** Creates a fresh agent session with this text — the same path the quick-input widget uses. */
    private fun sendToAgent(
        text: String,
    ) {
        if (text.isBlank()) return
        val status = statusText ?: return
        val app = application as MushreaCodeApplication
        val runtime = app.runtimeRegistry.selected.value
        if (runtime == null) {
            status.text = getString(R.string.widget_no_runtime)
            status.setTextColor(0xFFFF5555.toInt())
            return
        }
        status.text = getString(R.string.widget_sending)
        status.setTextColor(0xFFAAAAAA.toInt())
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val title = getString(R.string.widget_session_title, text.take(30))
                        val session = runtime.createSession(title = title)
                        runtime.sendMessage(session.id, PromptRequest(text = text))
                    }
                }
            result.onSuccess {
                status.text = getString(R.string.widget_sent)
                status.setTextColor(0xFF55FF55.toInt())
                finishAfterDelay()
            }.onFailure { error ->
                status.text = getString(R.string.widget_error, error.message)
                status.setTextColor(0xFFFF5555.toInt())
            }
        }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "error", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun finishAfterDelay(delayMillis: Long = 900L) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, delayMillis)
    }

}
