package com.mushrea.code.device.mirror

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mushrea.code.R

/**
 * Full-screen display for the live view-only mirror. Closing the screen stops the session: a
 * mirror nobody can see keeps decoding nothing the user asked for, and a backgrounded one would
 * silently carry the other phone's screen around.
 */
class MirrorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MirrorScreen()
        }
    }

    override fun onStop() {
        // Left the screen (home, another app, lock): the mirror stops rather than running unseen.
        if (!isChangingConfigurations) {
            ScreenMirrorSession.stop()
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) ScreenMirrorSession.detachSurface()
        super.onDestroy()
    }
}

@Composable
private fun MirrorScreen() {
    val state by ScreenMirrorSession.state.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    ScreenMirrorSession.attachSurface(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    ScreenMirrorSession.detachSurface()
                                }
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // View-Only is the point of this screen; say it on the screen itself.
            Text(
                text = stringResource(R.string.mirror_view_only_notice),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            val label =
                when (val current = state) {
                    is ScreenMirrorSession.State.Starting -> stringResource(R.string.mirror_starting)
                    is ScreenMirrorSession.State.Live ->
                        stringResource(R.string.mirror_live, current.take)
                    is ScreenMirrorSession.State.Reconnecting ->
                        stringResource(R.string.mirror_reconnecting, current.take + 1)
                    is ScreenMirrorSession.State.Failed ->
                        stringResource(R.string.mirror_failed, current.reason)
                    ScreenMirrorSession.State.Idle, ScreenMirrorSession.State.Stopped -> null
                }
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.mirror_view_only),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f),
            )
            val activity = LocalContext.current as? Activity
            Button(
                onClick = {
                    ScreenMirrorSession.stop()
                    activity?.finish()
                },
            ) {
                Text(stringResource(R.string.mirror_stop_action))
            }
        }
    }
}
