package com.mushrea.code.device.mirror

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mushrea.code.R
import kotlinx.coroutines.launch

/**
 * Full-screen display for the scrcpy session: the other phone's live screen, rendered on a
 * SurfaceView, with touches and scrolls on it injected back onto the other phone through the
 * session's control socket. The control bar carries the hardware keys people actually need;
 * leaving the screen stops the session, so nothing keeps running unseen.
 */
class RemoteControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteControlScreen()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            ScrcpySession.stop()
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) ScrcpySession.detachSurface()
        super.onDestroy()
    }
}

@Composable
private fun RemoteControlScreen() {
    val state by ScrcpySession.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    ScrcpySession.attachSurface(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    ScrcpySession.detachSurface()
                                }
                            },
                        )
                        setOnTouchListener { view, event ->
                            val width = view.width.coerceAtLeast(1)
                            val height = view.height.coerceAtLeast(1)
                            val frameW = ScrcpySession.frameWidth
                            val frameH = ScrcpySession.frameHeight
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                    val x = (event.getX(event.actionIndex) * frameW / width).toInt()
                                    val y = (event.getY(event.actionIndex) * frameH / height).toInt()
                                    scope.launch { ScrcpySession.injectTouch(ScrcpyControlMessages.ACTION_DOWN, pointerId(event), x, y, event.pressure) }
                                    true
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    for (history in 0 until event.historySize) {
                                        val hx = (event.getHistoricalX(0, history) * frameW / width).toInt()
                                        val hy = (event.getHistoricalY(0, history) * frameH / height).toInt()
                                        scope.launch { ScrcpySession.injectTouch(ScrcpyControlMessages.ACTION_MOVE, pointerId(event), hx, hy, event.pressure) }
                                    }
                                    val x = (event.x * frameW / width).toInt()
                                    val y = (event.y * frameH / height).toInt()
                                    scope.launch { ScrcpySession.injectTouch(ScrcpyControlMessages.ACTION_MOVE, pointerId(event), x, y, event.pressure) }
                                    true
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                                    val x = (event.x * frameW / width).toInt()
                                    val y = (event.y * frameH / height).toInt()
                                    scope.launch { ScrcpySession.injectTouch(ScrcpyControlMessages.ACTION_UP, pointerId(event), x, y, event.pressure) }
                                    true
                                }
                                MotionEvent.ACTION_SCROLL -> {
                                    val x = (event.x * frameW / width).toInt()
                                    val y = (event.y * frameH / height).toInt()
                                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                                    val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                                    scope.launch { ScrcpySession.injectScroll(x, y, h, v) }
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val label =
                when (val current = state) {
                    is ScrcpySession.State.Starting -> stringResource(R.string.remote_starting)
                    is ScrcpySession.State.Live ->
                        stringResource(R.string.remote_live, current.deviceName)
                    is ScrcpySession.State.Reconnecting ->
                        stringResource(R.string.remote_reconnecting)
                    is ScrcpySession.State.Failed ->
                        stringResource(R.string.remote_failed, current.reason)
                    ScrcpySession.State.Idle, ScrcpySession.State.Stopped -> null
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
        ControlBar()
    }
}

private fun pointerId(event: MotionEvent): Long =
    if (event.pointerCount > 0) event.getPointerId(if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) event.actionIndex else 0).toLong() else 0L

@Composable
private fun ControlBar() {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { scope.launch { ScrcpySession.tapKey(ScrcpyControlMessages.KEYCODE_BACK) } },
        ) {
            Text(stringResource(R.string.remote_back))
        }
        OutlinedButton(
            onClick = { scope.launch { ScrcpySession.tapKey(ScrcpyControlMessages.KEYCODE_HOME) } },
        ) {
            Text(stringResource(R.string.remote_home))
        }
        OutlinedButton(
            onClick = { scope.launch { ScrcpySession.tapKey(ScrcpyControlMessages.KEYCODE_APP_SWITCH) } },
        ) {
            Text(stringResource(R.string.remote_recents))
        }
        Button(
            onClick = {
                ScrcpySession.stop()
                activity?.finish()
            },
        ) {
            Text(stringResource(R.string.remote_stop))
        }
    }
}
