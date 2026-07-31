package com.voicetoinvoice.app.domain.action

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hand-off from background voice processing to the UI for actions that need an Activity.
 *
 * `SttWorker` runs under WorkManager and can resolve "send Ramesh his bill" perfectly well, but it
 * cannot launch the WhatsApp intent itself: starting an activity needs a UI context, and more
 * importantly the shopkeeper's own tap on Send is the consent checkpoint. There is no silent-send
 * API for a non-Business-API app, and auto-messaging a shopkeeper's customers from their own
 * number is not something to do on their behalf.
 *
 * `replay = 1` with a small buffer so an action resolved while the Activity is being recreated
 * (rotation, returning from background) is still delivered rather than dropped -- silently losing
 * the action would reproduce exactly the bug this whole path exists to fix.
 */
object PendingActions {

    private val _actions = MutableSharedFlow<FollowUp.LaunchAction>(
        replay = 1,
        extraBufferCapacity = 4
    )

    val actions: SharedFlow<FollowUp.LaunchAction> = _actions.asSharedFlow()

    fun enqueue(action: FollowUp.LaunchAction) {
        _actions.tryEmit(action)
    }

    /** Clears the replay cache once the UI has acted, so it does not re-fire on next collect. */
    fun consume() {
        _actions.resetReplayCache()
    }
}
