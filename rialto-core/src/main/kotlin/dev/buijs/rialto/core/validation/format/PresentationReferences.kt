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
 * The references of the `views` section: the operations a view loads, submits, edits and lists with, the views
 * it links, opens and shows as tabs, and the events that refresh it. The context of a slot is a type expression,
 * checked by [TypeExpressionChecks].
 */
internal fun Contract.viewReferences(): List<Reference> =
    collectReferences {
        views.forEach { (name, view) ->
            val at = Pointer.of(Keys.VIEWS, name)
            operation(view.load, at / Keys.LOAD)
            operation(view.submit, at / Keys.SUBMIT)

            view.fields.forEach { (field, declaration) ->
                val fieldAt = at / Keys.FIELDS / field
                operation(declaration.edit, fieldAt / Keys.EDIT)
                view(declaration.link, fieldAt / Keys.LINK)
                operation(declaration.options?.operation, fieldAt / Keys.OPTIONS / Keys.OPERATION)
            }

            view.actions.forEach { (action, declaration) ->
                operation(declaration.operation, at / Keys.ACTIONS / action / Keys.OPERATION)
            }

            each(Kind.VIEW, view.tabs, at / Keys.TABS)

            val list = view.list
            operation(list?.operation, at / Keys.LIST / Keys.OPERATION)
            view(list?.open, at / Keys.LIST / Keys.OPEN)
            list?.actions?.forEach { (action, declaration) ->
                operation(declaration.operation, at / Keys.LIST / Keys.ACTIONS / action / Keys.OPERATION)
            }

            each(Kind.EVENT, view.refreshOn, at / Keys.REFRESH_ON)
        }
    }

/** The references of the `navigation` section: the home view, and the view and badge operation of each menu entry. */
internal fun Contract.navigationReferences(): List<Reference> =
    collectReferences {
        val navigation = navigation ?: return@collectReferences
        view(navigation.home, Pointer.of(Keys.NAVIGATION, Keys.HOME))
        navigation.menus.forEach { (menu, entries) ->
            entries.forEachIndexed { index, entry ->
                val at = Pointer.of(Keys.NAVIGATION, menu, index)
                view(entry.view, at / Keys.VIEW)
                operation(entry.badge, at / Keys.BADGE)
            }
        }
    }
