package com.mushrea.code.core.api

/**
 * A GitHub issue/PR reference (``type`` #``number``) the agent mentioned in a reply.
 *
 * Lives in ``core`` rather than the workspace feature so the API client that discovers these
 * references does not have to depend on a feature package to build them.
 */
data class GitHubReference(
    val type: String,
    val number: Int,
    val title: String,
    val url: String,
)
