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

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLFactory
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLReadFeature
import tools.jackson.dataformat.yaml.YAMLSchema
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.kotlinModule

/**
 * The Jackson mappers between documents and the [Contract][dev.buijs.rialto.core.model.Contract] model, configured in one place.
 *
 * Every reader and writer in this package uses these, so a contract reads the same everywhere: in the
 * validator, the bundle and `rialto.lock`.
 */
object ContractMapper {
    /**
     * Plain JSON as the specification defines it: no comments, trailing commas or single quotes. Duplicate keys are
     * caught by [LocatedDocument] itself, so the defaults need no changes.
     */
    val jsonFactory: JsonFactory = JsonFactory()

    /**
     * YAML 1.2 with its core schema: `~` and `Null` are null, `yes` is a string, `0444` is 444 and `0o14` is 12.
     * YAML 1.1 reads `yes` as true and `0444` as an octal number, which would change what an author wrote.
     */
    val yamlFactory: YAMLFactory =
        YAMLFactory
            .builder()
            .yamlSchema(YAMLSchema.CORE)
            .disable(YAMLReadFeature.PARSE_OCTAL_NUMBERS)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .enable(YAMLWriteFeature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .build()

    /** Reads trees into the model; unknown keys (extensions) are left out of the model. */
    val json: ObjectMapper =
        JsonMapper
            .builder(jsonFactory)
            .addModule(kotlinModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build()

    /** Writes the model as YAML, in declaration order, leaving out absent and empty values. */
    val yaml: ObjectMapper =
        YAMLMapper
            .builder(yamlFactory)
            .addModule(kotlinModule())
            .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_EMPTY) }
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build()
}
