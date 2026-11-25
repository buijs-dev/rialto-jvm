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
package dev.buijs.rialto.core.document

import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.CollectionEndEvent
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver
import java.util.Optional

/**
 * A YAML contract or fragment uses only what JSON can express (K1.11): every key a string, no anchors or aliases,
 * and no tags other than those of the YAML 1.2 core schema. Each of those has no JSON form, so reading on would
 * drop or change what the author wrote; an alias can also make a document that grows exponentially when expanded.
 *
 * It reads the events of snakeyaml-engine, the parser under Jackson, because Jackson's tokens no longer say how a
 * key was written: `1:` and `"1":` both arrive as the name "1". [LocatedDocument] runs it after Jackson has read
 * the document, so a document that is no valid YAML keeps Jackson's message.
 */
internal object JsonYaml {
    /** YAML that JSON cannot express, at [line] and [column] (from 1). */
    class NotJson(
        val reason: String,
        val line: Int,
        val column: Int,
    ) : IllegalArgumentException(reason)

    private const val CORE = "tag:yaml.org,2002:"

    /** The tags of the YAML 1.2 core schema, as the parser reports `!!str` and the others. */
    private val coreTags = listOf("str", "int", "float", "bool", "null", "map", "seq").map { CORE + it }.toSet()

    // Without merge keys: `<<` is a plain string here, and merging needs an alias, which is rejected anyway.
    private val resolver = CoreScalarResolver(false)

    /** A mapping or list that is open; in a mapping, whether its next node is a key. */
    private class Open(
        val mapping: Boolean,
    ) {
        var key = mapping
    }

    /** Throws [NotJson] at the first element of [text] that JSON cannot express. */
    fun check(text: String) {
        val open = ArrayDeque<Open>()
        Parse(LoadSettings.builder().build()).parseString(text).forEach { event ->
            when (event) {
                is AliasEvent -> {
                    throw notJson(event, "The alias '*${event.alias.value}'")
                }
                is ScalarEvent -> {
                    if (node(event, event.tag, open) && !isString(event)) {
                        throw notJson(event, "The key '${event.value}', which is not a string,")
                    }
                }
                is CollectionStartEvent -> {
                    // A mapping or list as a key never gets here: Jackson rejects it while reading.
                    node(event, event.tag, open)
                    open.addLast(Open(event is MappingStartEvent))
                }
                is CollectionEndEvent -> {
                    open.removeLast()
                }
            }
        }
    }

    /** Checks the anchor and [tag] of a node, and returns whether it is a key; the next node of its mapping is not. */
    private fun node(
        event: NodeEvent,
        tag: Optional<String>,
        open: ArrayDeque<Open>,
    ): Boolean {
        if (event.anchor.isPresent) {
            throw notJson(event, "The anchor '&${event.anchor.get().value}'")
        }

        if (tag.isPresent && tag.get() !in coreTags) {
            throw notJson(event, "The tag '${tag.get()}'")
        }

        val parent = open.lastOrNull() ?: return false
        val key = parent.key
        parent.key = parent.mapping && !key
        return key
    }

    /** Whether a scalar is a string: by its tag when it has one, else a quoted scalar is, a plain one by its text. */
    private fun isString(event: ScalarEvent): Boolean =
        when {
            event.tag.isPresent -> event.tag.get() == CORE + "str"
            event.isPlain -> resolver.resolve(event.value, true) == Tag.STR
            else -> true
        }

    /** [what] at the place of [event], as the [NotJson] to throw there. */
    private fun notJson(
        event: Event,
        what: String,
    ): NotJson {
        val mark = event.startMark.get()
        return NotJson("$what has no JSON form", mark.line + 1, mark.column + 1)
    }
}
