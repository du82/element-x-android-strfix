/*
 * Copyright (c) 2022 New Vector Ltd
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

package io.element.android.x.core.compose

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

open class PairCombinedPreviewParameter<T1, T2>(
    private val provider: Pair<PreviewParameterProvider<T1>, PreviewParameterProvider<T2>>
) : PreviewParameterProvider<Pair<T1, T2>> {
    override val values: Sequence<Pair<T1, T2>>
        get() = provider.first.values.flatMap { first ->
            provider.second.values.map { second ->
                first to second
            }
        }
}
