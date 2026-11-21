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

package dev.buijs.rialto.contract

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.JsonNode

data class Field(
    val edit: String? = null,
    val partial: Boolean = false,
    val hidden: String? = null,
    val disabled: String? = null,
    val component: String? = null,
    val label: String? = null,

    /** When the field is shown (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val visibleWhen: Map<String, JsonNode> = emptyMap(),

    /** When the field can be changed (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val enabledWhen: Map<String, JsonNode> = emptyMap(),

    /** The view the value links to (U5.11). */
    val link: String? = null,

    /** The elements the user chooses the referenced entity from (U2.11). */
    val options: FieldOptions? = null,
)

/** A choice of elements of [operation], each shown by its [label] path (U2.11). */
data class FieldOptions(
    val operation: String,
    val label: String,
)

data class Action(
    val operation: String,
    val confirm: Boolean = false,
    val pending: String? = null,
    val after: JsonNode? = null,

    /** Per JSON Pointer in the request body, the fixed value the action sends there (U3.9). */
    @get:JsonInclude(value = JsonInclude.Include.NON_EMPTY, content = JsonInclude.Include.ALWAYS)
    val values: Map<String, JsonNode> = emptyMap(),

    /** When the action is shown (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val visibleWhen: Map<String, JsonNode> = emptyMap(),

    /** When the action can be triggered (U3.11). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val enabledWhen: Map<String, JsonNode> = emptyMap(),

    /** For a row action: per operation parameter, its source, `route.<name>` or `row.<path>` (U3.10). */
    @get:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val parameters: Map<String, String> = emptyMap(),
)

data class Slot(
    val context: String,
    val mode: String,
)

data class ListView(
    val operation: String,
    val columns: List<String> = emptyList(),
    val filters: Map<String, FilterControl> = emptyMap(),
    val pageSize: Int? = null,

    /** Filters the view always sends, with the actor as value (U6.5). */
    val where: List<FixedFilter> = emptyList(),

    /** Actions offered on each row (U3.10). */
    val actions: Map<String, Action> = emptyMap(),

    /** The view a row opens (U5.11). */
    val open: String? = null,
)

/** A filter a list view always sends: [field] [operator] the signed-in actor (U6.5). */
data class FixedFilter(
    val field: String,
    val operator: String,
    val value: String,
)

data class FilterControl(
    val field: String,
    val operator: String,
)

/**
 * A view under `views` (presentation profile, U): one screen of the application, the operation that loads its
 * data ([load]) or submits it ([submit]), its [fields] and [actions], or a [list] of rows.
 */
data class View(
    val route: String? = null,
    val load: String? = null,
    val submit: String? = null,
    val fields: Map<String, Field> = emptyMap(),
    val actions: Map<String, Action> = emptyMap(),

    /** Views shown as tabs of this view, in order (U5.10). */
    val tabs: List<String> = emptyList(),

    val slots: Map<String, Slot> = emptyMap(),
    val dirty: Map<String, Boolean> = emptyMap(),
    val replaceable: Boolean = false,
    val refreshOn: List<String> = emptyList(),
    val list: ListView? = null,
    val requires: List<String> = emptyList(),
)

/** The menu of the application (U5.9): the view shown first and the entries of both menus. */
data class Navigation(
    val home: String? = null,
    val primary: List<NavigationEntry> = emptyList(),
    val secondary: List<NavigationEntry> = emptyList(),
) {
    /** Both menus by their key, `primary` first. */
    @get:JsonIgnore
    val menus: List<Pair<String, List<NavigationEntry>>>
        get() = listOf(Keys.PRIMARY to primary, Keys.SECONDARY to secondary)
}

/** One menu entry: a [view], its [icon] and [label] references, and a count operation as [badge]. */
data class NavigationEntry(
    val view: String,
    val icon: String? = null,
    val label: String? = null,
    val badge: String? = null,
)
