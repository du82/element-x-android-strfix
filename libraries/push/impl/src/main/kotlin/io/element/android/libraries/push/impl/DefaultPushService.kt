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

package io.element.android.libraries.push.impl

import com.squareup.anvil.annotations.ContributesBinding
import io.element.android.libraries.di.AppScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.push.api.PushService
import io.element.android.libraries.push.impl.notifications.NotificationDrawerManager
import io.element.android.libraries.push.providers.api.PushProvider
import javax.inject.Inject

@ContributesBinding(AppScope::class)
class DefaultPushService @Inject constructor(
    private val notificationDrawerManager: NotificationDrawerManager,
    private val pushersManager: PushersManager,
    private val pushProviders: Set<@JvmSuppressWildcards PushProvider>,
) : PushService {
    override fun notificationStyleChanged() {
        notificationDrawerManager.notificationStyleChanged()
    }

    override fun getAvailablePushProviders(): List<PushProvider> {
        return pushProviders.sortedBy { it.index }
    }

    override suspend fun registerWith(matrixClient: MatrixClient, pushProvider: PushProvider, distributorName: String) {
        // TODO Get current push provider, compare with provided one, then unregister and register if different, and store change

        pushProvider.registerWith(matrixClient, distributorName)
    }

    override suspend fun testPush() {
        pushersManager.testPush()
    }
}
