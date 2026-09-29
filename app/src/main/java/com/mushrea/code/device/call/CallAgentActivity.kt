package com.mushrea.code.device.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mushrea.code.R
import com.mushrea.code.ui.theme.MushreaCodeTheme
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * The call-agent surface (spec sections 13-19/27): the live call at the top — state, goals,
 * answers, both sides' last words, and the TAKE OVER / END / STOP controls — then the incoming
 * rules, the privacy switches, and the identity fields. Everything reads and writes
 * [CallAgentStore]; the conversation itself stays in the foreground service.
 */
class CallAgentActivity : ComponentActivity() {
    private lateinit var store: CallAgentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CallAgentStore(this)
        setContent {
            MushreaCodeTheme {
                CallAgentScreen(
                    store = store,
                    onServiceAction = { action -> startService(Intent(this, CallAgentService::class.java).setAction(action)) },
                )
            }
        }
    }
}

@Composable
private fun CallAgentScreen(
    store: CallAgentStore,
    onServiceAction: (String) -> Unit,
) {
    var live by remember { mutableStateOf(store.readLiveState()) }
    var rules by remember { mutableStateOf(store.readCallerRules()) }
    var allowKnown by remember { mutableStateOf(store.readAllowKnownContactsByDefault()) }
    var privacy by remember { mutableStateOf(store.readPrivacy()) }
    var userName by remember { mutableStateOf(store.readUserDisplayName()) }
    var template by remember { mutableStateOf(store.readIdentityTemplate()) }
    var newRuleName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            live = store.readLiveState()
            delay(1_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.call_agent_screen_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        // -- Live call --------------------------------------------------------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val conversation = live.optJSONObject("conversation")
                    if (conversation == null || CallStateMachine.isTerminal(currentState(live))) {
                        Text(stringResource(R.string.call_agent_no_active_call), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            text = stringResource(R.string.call_agent_state_label) + ": " + live.optString("state"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        conversation.optString("target").takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.call_agent_target_label) + ": $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        conversation.optString("purpose").takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.call_agent_purpose_label) + ": $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        val goals = conversation.optJSONArray("goals")
                        goals?.let { array ->
                            for (i in 0 until array.length()) {
                                val goal = array.optJSONObject(i) ?: continue
                                val answer = goal.optString("answer").takeIf { it.isNotBlank() } ?: "…"
                                Text("• ${goal.optString("question")} → $answer", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        conversation.optString("last_caller").takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.call_agent_caller_said) + ": $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onServiceAction(CallAgentService.ACTION_TAKE_OVER) }) {
                            Text(stringResource(R.string.call_agent_action_take_over))
                        }
                        OutlinedButton(onClick = { onServiceAction(CallAgentService.ACTION_END_CALL) }) {
                            Text(stringResource(R.string.call_agent_action_end_call))
                        }
                    }
                    Button(
                        onClick = { onServiceAction(CallAgentService.ACTION_STOP_AGENT) },
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                    ) {
                        Text(stringResource(R.string.call_agent_action_stop_agent))
                    }
                }
            }
        }

        // -- Incoming rules ----------------------------------------------------
        item {
            Text(
                stringResource(R.string.call_agent_rules_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(rules.entries.sortedBy { it.key }) { (label, action) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text =
                                when (action) {
                                    CallPolicy.IncomingAction.ALLOW_ASSISTANT -> stringResource(R.string.call_agent_rule_allow)
                                    CallPolicy.IncomingAction.TAKE_MESSAGE -> stringResource(R.string.call_agent_rule_take_message)
                                    CallPolicy.IncomingAction.DO_NOT_ANSWER -> stringResource(R.string.call_agent_rule_do_not_answer)
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = {
                        val next =
                            when (action) {
                                CallPolicy.IncomingAction.DO_NOT_ANSWER -> CallPolicy.IncomingAction.TAKE_MESSAGE
                                CallPolicy.IncomingAction.TAKE_MESSAGE -> CallPolicy.IncomingAction.ALLOW_ASSISTANT
                                CallPolicy.IncomingAction.ALLOW_ASSISTANT -> CallPolicy.IncomingAction.DO_NOT_ANSWER
                            }
                        rules = rules.toMutableMap().apply { put(label, next) }
                        store.writeCallerRules(rules)
                    }) { Text(stringResource(R.string.call_agent_rule_change)) }
                    OutlinedButton(onClick = {
                        rules = rules.toMutableMap().apply { remove(label) }
                        store.writeCallerRules(rules)
                    }) { Text("✕") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newRuleName,
                    onValueChange = { newRuleName = it },
                    label = { Text(stringResource(R.string.call_agent_rules_hint)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        val name = newRuleName.trim()
                        if (name.isNotEmpty()) {
                            rules = rules.toMutableMap().apply { put(name, CallPolicy.IncomingAction.TAKE_MESSAGE) }
                            store.writeCallerRules(rules)
                            newRuleName = ""
                        }
                    },
                ) { Text(stringResource(R.string.call_agent_add_rule)) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allowKnown,
                    onCheckedChange = {
                        allowKnown = it
                        store.writeAllowKnownContactsByDefault(it)
                    },
                )
                Text(stringResource(R.string.call_agent_allow_known_default), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // -- Privacy -----------------------------------------------------------
        item {
            Text(
                stringResource(R.string.call_agent_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = privacy.optBoolean("store_transcript"),
                    onCheckedChange = {
                        privacy = JSONObject(privacy.toString()).put("store_transcript", it)
                        store.writePrivacy(privacy.optBoolean("store_transcript"), privacy.optBoolean("store_summary"))
                    },
                )
                Text(stringResource(R.string.call_agent_store_transcript), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = privacy.optBoolean("store_summary"),
                    onCheckedChange = {
                        privacy = JSONObject(privacy.toString()).put("store_summary", it)
                        store.writePrivacy(privacy.optBoolean("store_transcript"), it)
                    },
                )
                Text(stringResource(R.string.call_agent_store_summary), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // -- Identity ------------------------------------------------------------
        item {
            Text(
                stringResource(R.string.call_agent_identity_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            OutlinedTextField(
                value = userName,
                onValueChange = {
                    userName = it
                    store.writeUserDisplayName(it.trim())
                },
                label = { Text(stringResource(R.string.call_agent_user_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = template,
                onValueChange = {
                    template = it
                    store.writeIdentityTemplate(it)
                },
                label = { Text(stringResource(R.string.call_agent_identity_template_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Text(stringResource(R.string.call_agent_identity_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun currentState(live: JSONObject): CallStateMachine.State =
    CallStateMachine.State.entries.firstOrNull { it.name == live.optString("state") } ?: CallStateMachine.State.IDLE
