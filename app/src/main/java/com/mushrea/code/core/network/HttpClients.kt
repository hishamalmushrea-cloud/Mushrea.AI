package com.mushrea.code.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's shared HTTP clients.
 *
 * Every collaborator used to construct its own `OkHttpClient()`, which meant a private connection
 * pool and dispatcher per call site plus OkHttp's 10-second defaults — wrong in both directions: a
 * runtime download may legitimately take minutes, while a hung speech request should fail fast.
 * Three profiles cover every call the app makes, and each is built once so the app shares one pool.
 *
 * Not for local tool code that must honour a *per-call* timeout: the device agent's `net_*` tools
 * build a client from the tool's own `timeout_seconds` argument on purpose.
 */
object HttpClients {
    /**
     * Health checks, catalogs, release metadata, GitHub, the runtime API. Chatty calls where a dead
     * endpoint has to surface as an error instead of a spinner that never stops.
     */
    val api: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Multi-megabyte artifacts: the runtime rootfs, agent releases, the Vosk model. No read timeout,
     * because a slow mobile network is not an error; these calls report progress and are cancelled
     * by their caller when the user leaves.
     */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Small user-facing requests (text to speech) where waiting is worse than failing: the whole
     * call is bounded, so a stalled endpoint cannot leave a voice turn hanging.
     */
    val short: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
