/*
 * Copyright (c) 2023 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.element.android.libraries.push.impl.notifications

import io.element.android.libraries.androidutils.throttler.FirstThrottler
import io.element.android.libraries.core.cache.CircularCache
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.di.AppScope
import io.element.android.libraries.di.SingleIn
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.push.api.notifications.NotificationDrawerManager
import io.element.android.libraries.push.impl.notifications.model.NotifiableEvent
import io.element.android.services.appnavstate.api.AppNavigationStateService
import io.element.android.services.appnavstate.api.NavigationState
import io.element.android.services.appnavstate.api.currentSessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * The NotificationDrawerManager receives notification events as they arrived (from event stream or fcm) and
 * organise them in order to display them in the notification drawer.
 * Events can be grouped into the same notification, old (already read) events can be removed to do some cleaning.
 */
@SingleIn(AppScope::class)
class DefaultNotificationDrawerManager @Inject constructor(
    private val notifiableEventProcessor: NotifiableEventProcessor,
    private val notificationRenderer: NotificationRenderer,
    private val notificationEventPersistence: NotificationEventPersistence,
    private val filteredEventDetector: FilteredEventDetector,
    private val appNavigationStateService: AppNavigationStateService,
    private val coroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val buildMeta: BuildMeta,
    private val matrixClientProvider: MatrixClientProvider,
) : NotificationDrawerManager {
    /**
     * Lazily initializes the NotificationState as we rely on having a current session in order to fetch the persisted queue of events.
     */
    private val notificationState by lazy { createInitialNotificationState() }
    private val firstThrottler = FirstThrottler(200)

    // TODO EAx add a setting per user for this
    private var useCompleteNotificationFormat = true

    init {
        // Observe application state
        coroutineScope.launch {
            appNavigationStateService.appNavigationState
                .collect { onAppNavigationStateChange(it.navigationState) }
        }
    }

    private var currentAppNavigationState: NavigationState? = null

    private fun onAppNavigationStateChange(navigationState: NavigationState) {
        when (navigationState) {
            NavigationState.Root -> {
                currentAppNavigationState?.currentSessionId()?.let { sessionId ->
                    // User signed out, clear all notifications related to the session.
                    clearAllEvents(sessionId)
                }
            }
            is NavigationState.Session -> {}
            is NavigationState.Space -> {}
            is NavigationState.Room -> {
                // Cleanup notification for current room
                clearMessagesForRoom(navigationState.parentSpace.parentSession.sessionId, navigationState.roomId)
            }
            is NavigationState.Thread -> {
                onEnteringThread(
                    navigationState.parentRoom.parentSpace.parentSession.sessionId,
                    navigationState.parentRoom.roomId,
                    navigationState.threadId
                )
            }
        }
        currentAppNavigationState = navigationState
    }

    private fun createInitialNotificationState(): NotificationState {
        val queuedEvents = notificationEventPersistence.loadEvents(factory = { rawEvents ->
            NotificationEventQueue(rawEvents.toMutableList(), seenEventIds = CircularCache.create(cacheSize = 25))
        })
        val renderedEvents = queuedEvents.rawEvents().map { ProcessedEvent(ProcessedEvent.Type.KEEP, it) }.toMutableList()
        return NotificationState(queuedEvents, renderedEvents)
    }

    private fun NotificationEventQueue.onNotifiableEventReceived(notifiableEvent: NotifiableEvent) {
        if (buildMeta.lowPrivacyLoggingEnabled) {
            Timber.d("onNotifiableEventReceived(): $notifiableEvent")
        } else {
            Timber.d("onNotifiableEventReceived(): is push: ${notifiableEvent.canBeReplaced}")
        }

        if (filteredEventDetector.shouldBeIgnored(notifiableEvent)) {
            Timber.d("onNotifiableEventReceived(): ignore the event")
            return
        }

        add(notifiableEvent)
    }

    /**
     * Should be called as soon as a new event is ready to be displayed.
     * The notification corresponding to this event will not be displayed until
     * #refreshNotificationDrawer() is called.
     * Events might be grouped and there might not be one notification per event!
     */
    fun onNotifiableEventReceived(notifiableEvent: NotifiableEvent) {
        updateEvents {
            it.onNotifiableEventReceived(notifiableEvent)
        }
    }

    /**
     * Clear all known events and refresh the notification drawer.
     */
    fun clearAllMessagesEvents(sessionId: SessionId) {
        updateEvents {
            it.clearMessagesForSession(sessionId)
        }
    }

    /**
     * Clear all notifications related to the session and refresh the notification drawer.
     */
    fun clearAllEvents(sessionId: SessionId) {
        updateEvents {
            it.clearAllForSession(sessionId)
        }
    }

    /**
     * Should be called when the application is currently opened and showing timeline for the given roomId.
     * Used to ignore events related to that room (no need to display notification) and clean any existing notification on this room.
     * Can also be called when a notification for this room is dismissed by the user.
     */
    fun clearMessagesForRoom(sessionId: SessionId, roomId: RoomId) {
        updateEvents {
            it.clearMessagesForRoom(sessionId, roomId)
        }
    }

    override fun clearMembershipNotificationForSession(sessionId: SessionId) {
        updateEvents {
            it.clearMembershipNotificationForSession(sessionId)
        }
    }

    /**
     * Clear invitation notification for the provided room.
     */
    override fun clearMembershipNotificationForRoom(sessionId: SessionId, roomId: RoomId) {
        updateEvents {
            it.clearMembershipNotificationForRoom(sessionId, roomId)
        }
    }

    /**
     * Clear the notifications for a single event.
     */
    fun clearEvent(eventId: EventId) {
        updateEvents {
            it.clearEvent(eventId)
        }
    }

    /**
     * Should be called when the application is currently opened and showing timeline for the given threadId.
     * Used to ignore events related to that thread (no need to display notification) and clean any existing notification on this room.
     */
    private fun onEnteringThread(sessionId: SessionId, roomId: RoomId, threadId: ThreadId) {
        updateEvents {
            it.clearMessagesForThread(sessionId, roomId, threadId)
        }
    }

    // TODO EAx Must be per account
    fun notificationStyleChanged() {
        updateEvents {
            val newSettings = true // pushDataStore.useCompleteNotificationFormat()
            if (newSettings != useCompleteNotificationFormat) {
                // Settings has changed, remove all current notifications
                notificationRenderer.cancelAllNotifications()
                useCompleteNotificationFormat = newSettings
            }
        }
    }

    private fun updateEvents(action: DefaultNotificationDrawerManager.(NotificationEventQueue) -> Unit) {
        notificationState.updateQueuedEvents(this) { queuedEvents, _ ->
            action(queuedEvents)
        }
        coroutineScope.refreshNotificationDrawer()
    }

    private fun CoroutineScope.refreshNotificationDrawer() = launch {
        // Implement last throttler
        val canHandle = firstThrottler.canHandle()
        Timber.v("refreshNotificationDrawer(), delay: ${canHandle.waitMillis()} ms")
        withContext(dispatchers.io) {
            delay(canHandle.waitMillis())
            try {
                refreshNotificationDrawerBg()
            } catch (throwable: Throwable) {
                // It can happen if for instance session has been destroyed. It's a bit ugly to try catch like this, but it's safer
                Timber.w(throwable, "refreshNotificationDrawerBg failure")
            }
        }
    }

    private suspend fun refreshNotificationDrawerBg() {
        Timber.v("refreshNotificationDrawerBg()")
        val eventsToRender = notificationState.updateQueuedEvents(this) { queuedEvents, renderedEvents ->
            notifiableEventProcessor.process(queuedEvents.rawEvents(), renderedEvents).also {
                queuedEvents.clearAndAdd(it.onlyKeptEvents())
            }
        }

        if (notificationState.hasAlreadyRendered(eventsToRender)) {
            Timber.d("Skipping notification update due to event list not changing")
        } else {
            notificationState.clearAndAddRenderedEvents(eventsToRender)
            renderEvents(eventsToRender)
            persistEvents()
        }
    }

    private fun persistEvents() {
        notificationState.queuedEvents { queuedEvents ->
            notificationEventPersistence.persistEvents(queuedEvents)
        }
    }

    private suspend fun renderEvents(eventsToRender: List<ProcessedEvent<NotifiableEvent>>) {
        // Group by sessionId
        val eventsForSessions = eventsToRender.groupBy {
            it.event.sessionId
        }

        eventsForSessions.forEach { (sessionId, notifiableEvents) ->
            val currentUser = tryOrNull(
                onError = { Timber.e(it, "Unable to retrieve info for user ${sessionId.value}") },
                operation = {
                    val client = matrixClientProvider.getOrRestore(sessionId).getOrThrow()
                    // myUserDisplayName cannot be empty else NotificationCompat.MessagingStyle() will crash
                    val myUserDisplayName = client.loadUserDisplayName().getOrNull() ?: sessionId.value
                    val userAvatarUrl = client.loadUserAvatarURLString().getOrNull()
                    MatrixUser(
                        userId = sessionId,
                        displayName = myUserDisplayName,
                        avatarUrl = userAvatarUrl
                    )
                }
            ) ?: MatrixUser(
                userId = sessionId,
                displayName = sessionId.value,
                avatarUrl = null
            )

            notificationRenderer.render(currentUser, useCompleteNotificationFormat, notifiableEvents)
        }
    }
}
