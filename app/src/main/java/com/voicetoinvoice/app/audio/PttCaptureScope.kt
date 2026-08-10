package com.voicetoinvoice.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Capture work outlives the composable that started it. The 600ms idle-flush timer used to
 * run on rememberCoroutineScope(): navigating away between press and flush cancelled it and
 * stranded the burst group, which was then flushed minutes later by an unrelated press and
 * extracted a window from that earlier time. Process-lifetime scope, never cancelled.
 */
object PttCaptureScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
