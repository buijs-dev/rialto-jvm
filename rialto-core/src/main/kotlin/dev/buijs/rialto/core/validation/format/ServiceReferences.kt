/**
 * Copyright (c) 2021 - 2026 Buijs Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package dev.buijs.rialto.core.validation.format

import dev.buijs.rialto.core.model.Contract
import dev.buijs.rialto.core.model.Keys

/**
 * The references of the `operations` section: the REST operations it binds to, its input and output, its
 * subject, the successor of a deprecated operation, the events it emits and the operation that counts a list.
 */
internal fun Contract.operationReferences(): List<Reference> =
    collectReferences {
        operations.forEach { (name, operation) ->
            val at = Pointer.of(Keys.OPERATIONS, name)
            operation.rest?.all?.forEach { external(it, at / Keys.REST) }
            data(operation.input, at / Keys.INPUT)
            data(operation.outputType, at / Keys.OUTPUT)

            // The short form `subject: Type` is located at the subject itself, the long form at its type.
            val subject = operation.subject
            type(subject?.type, if (subject?.by == null) at / Keys.SUBJECT else at / Keys.SUBJECT / Keys.TYPE)

            operation(operation.deprecated?.successor, at / Keys.DEPRECATED / Keys.SUCCESSOR)
            each(Kind.EVENT, operation.emits, at / Keys.EMITS)
            operation(operation.list?.count?.operation, at / Keys.LIST / Keys.COUNT / Keys.OPERATION)
        }
    }

/**
 * The references of the `events`, `streams` and `subscriptions` sections: the data and channel of an event, the
 * events and channel of a stream, and the event and dead-letter channel of a subscription.
 */
internal fun Contract.eventReferences(): List<Reference> =
    collectReferences {
        events.forEach { (name, event) ->
            val at = Pointer.of(Keys.EVENTS, name)
            data(event.data, at / Keys.DATA)
            external(event.channel, at / Keys.CHANNEL)
        }

        streams.forEach { (name, stream) ->
            val at = Pointer.of(Keys.STREAMS, name)
            each(Kind.EVENT, stream.events, at / Keys.EVENTS)
            external(stream.channel, at / Keys.CHANNEL)
        }

        subscriptions.forEach { (name, subscription) ->
            val at = Pointer.of(Keys.SUBSCRIPTIONS, name)
            event(subscription.event, at / Keys.EVENT)
            external(subscription.delivery.deadLetter, at / Keys.DELIVERY / Keys.DEAD_LETTER)
        }
    }

/** The references of the `mappings` section: the source and target of each mapping, in the contract or a source. */
internal fun Contract.mappingReferences(): List<Reference> =
    collectReferences {
        mappings.forEachIndexed { index, mapping ->
            val at = Pointer.of(Keys.MAPPINGS, index)
            dataOrExternal(mapping.source, at / Keys.SOURCE)
            dataOrExternal(mapping.target, at / Keys.TARGET)
        }
    }
