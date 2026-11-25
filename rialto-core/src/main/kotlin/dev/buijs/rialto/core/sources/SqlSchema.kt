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

package dev.buijs.rialto.core.sources

/** The tables that are read so far, by lower-case name. */
private typealias Tables = MutableMap<String, Columns>

/** The columns of one table, by lower-case name, in declaration order. */
private typealias Columns = MutableMap<String, SqlColumn>

/** The words a table constraint starts with: a definition that starts with one is no column. */
private val CONSTRAINT_START =
    setOf("primary", "foreign", "unique", "check", "constraint", "exclude", "like")

/** The words that end the type of column definition. */
private val COLUMN_CONSTRAINT =
    setOf(
        "not",
        "null",
        "primary",
        "default",
        "references",
        "check",
        "unique",
        "constraint",
        "generated",
        "collate",
    )

private val SERIAL =
    setOf("serial", "bigserial", "smallserial", "serial4", "serial8", "serial2")

private val DOLLAR_TAG = Regex("""^\$[A-Za-z_]*\$""")

/**
 * A column of an SQL table. [type] is the type name in lower case without length or precision,
 * such as `varchar` or `timestamp with time zone`; [hasDefault] is true for a `DEFAULT`, a
 * generated column and an identity or serial column.
 */
data class SqlColumn(
    val name: String,
    val type: String,
    val notNull: Boolean = false,
    val primaryKey: Boolean = false,
    val hasDefault: Boolean = false,
) {
    /** Whether the column accepts null: it is neither `NOT NULL` nor part of the primary key. */
    val acceptsNull: Boolean get() = !notNull && !primaryKey
}

/** A table of an SQL schema, its columns by lower-case name in declaration order. */
data class SqlTable(
    val name: String,
    val columns: Map<String, SqlColumn>,
)

/**
 * Reads the schema that results from SQL statements in order (K1.9): `CREATE TABLE`, the `ALTER
 * TABLE` actions on columns, the primary key and the table name, and `DROP TABLE`. Every other
 * statement is skipped. Names are compared in lower case without schema or quotes.
 *
 * The persistence profile maps entities onto tables and columns, and the contract refers to those as
 * `<source>#table.column`. To check such a reference, and the type and nullability of a column, the tooling needs
 * the schema without a database: this is a small reader of the DDL found in migrations, not a full SQL parser.
 */
object SqlSchema {
    /** The tables after running [sql], by lower-case name. */
    fun read(sql: String): Map<String, SqlTable> {
        val tables = linkedMapOf<String, Columns>()
        statements(sql).forEach { statement -> applyStatement(tables, tokens(statement)) }

        return tables.mapValues { (name, columns) -> SqlTable(name, columns.toMap()) }
    }

    private fun applyStatement(
        tables: Tables,
        tokens: List<String>,
    ) {
        val words = Words(tokens)
        when {
            words.accept("create") -> createTable(tables, words)
            words.accept("alter", "table") -> alterTable(tables, words)
            words.accept("drop", "table") -> dropTable(tables, words)
        }
    }

    private fun createTable(
        tables: Tables,
        words: Words,
    ) {
        // CREATE [GLOBAL | LOCAL] [TEMP | TEMPORARY | UNLOGGED] TABLE
        while (words.peek() in setOf("global", "local", "temp", "temporary", "unlogged")) {
            words.next()
        }

        if (!words.accept("table")) {
            return
        }

        val ifNotExists = words.accept("if", "not", "exists")
        val name = identifier(words.next() ?: return)
        val body = words.next()?.takeIf { it.startsWith("(") } ?: return
        if (ifNotExists && name in tables) {
            return
        }

        val columns = linkedMapOf<String, SqlColumn>()
        val primaryKey = mutableListOf<String>()
        split(body.substring(1, body.length - 1)).forEach { definition ->
            // A definition is never blank: split leaves those out.
            val parts = tokens(definition)
            if (parts.first().lowercase() in CONSTRAINT_START) {
                primaryKey += primaryKeyOf(parts).orEmpty()
            } else {
                column(parts)?.let { columns[it.name] = it }
            }
        }

        // A table constraint may stand before the columns it names, so it is applied once every column is read.
        primaryKey.forEach { columns.markPrimaryKey(it) }

        tables[name] = columns
    }

    private fun alterTable(
        tables: Tables,
        words: Words,
    ) {
        words.skip("if", "exists")
        words.skip("only")
        val name = identifier(words.next() ?: return)
        // RENAME TO renames the table itself; it is not combined with other actions.
        if (words.accept("rename", "to")) {
            val renamed = identifier(words.next() ?: return)
            tables.remove(name)?.let { tables[renamed] = it }

            return
        }

        val rest = words.rest()
        val columns = tables[name] ?: return
        split(rest.joinToString(" ")).forEach { action ->
            alterAction(columns, Words(tokens(action)))
        }
    }

    private fun alterAction(
        columns: Columns,
        words: Words,
    ) {
        when {
            words.accept("add") -> {
                val parts = words.rest()
                if (parts.isEmpty()) {
                    return
                }

                if (parts.first().lowercase() in CONSTRAINT_START) {
                    primaryKeyOf(parts)?.forEach { columns.markPrimaryKey(it) }

                    return
                }

                val definition = Words(parts)
                definition.skip("column")
                val ifNotExists = definition.accept("if", "not", "exists")
                val column =
                    column(definition.rest())
                        ?: return

                if (ifNotExists && column.name in columns) {
                    return
                }

                columns[column.name] = column
            }
            words.accept("drop") -> {
                if (words.peek() == "constraint") {
                    return
                }

                words.skip("column")
                words.skip("if", "exists")
                columns.remove(identifier(words.next() ?: return))
            }
            words.accept("rename") -> {
                if (words.peek() == "constraint") {
                    return
                }

                words.skip("column")
                val from = identifier(words.next() ?: return)
                if (!words.accept("to")) {
                    return
                }

                val to = identifier(words.next() ?: return)
                columns.remove(from)?.let { columns[to] = it.copy(name = to) }
            }
            words.accept("alter") -> {
                words.skip("column")
                val name = identifier(words.next() ?: return)
                val column = columns[name] ?: return
                alterColumn(column, words)?.let { columns[name] = it }
            }
        }
    }

    private fun alterColumn(
        column: SqlColumn,
        words: Words,
    ): SqlColumn? =
        when {
            words.accept("set", "not", "null") -> {
                column.copy(notNull = true)
            }
            words.accept("drop", "not", "null") -> {
                column.copy(notNull = false)
            }
            words.accept("set", "default") -> {
                column.copy(hasDefault = true)
            }
            words.accept("drop", "default") -> {
                column.copy(hasDefault = false)
            }
            words.accept("drop", "identity") -> {
                column.copy(hasDefault = false)
            }
            words.accept("add", "generated") -> {
                column.copy(notNull = true, hasDefault = true)
            }
            words.accept("set", "data", "type") || words.accept("type") -> {
                column.copy(type = typeName(words.rest()))
            }
            else -> {
                null
            }
        }

    private fun dropTable(
        tables: Tables,
        words: Words,
    ) {
        words.skip("if", "exists")
        // DROP TABLE a, b [CASCADE]: the names are separated by commas.
        split(words.rest().joinToString(" ")).forEach { name ->
            tables.remove(identifier(tokens(name).first()))
        }
    }

    /** A column definition: its name, its type and the column constraints after the type. */
    private fun column(parts: List<String>): SqlColumn? {
        if (parts.size < 2) {
            return null
        }

        val name = identifier(parts.first())
        val typeEnd =
            (1 until parts.size).firstOrNull { parts[it].lowercase() in COLUMN_CONSTRAINT }
                ?: parts.size
        val type = typeName(parts.subList(1, typeEnd))
        val constraints = Words(parts.subList(typeEnd, parts.size))

        var notNull = type in SERIAL
        var primaryKey = false
        var hasDefault = type in SERIAL
        while (!constraints.done) {
            when {
                constraints.accept("not", "null") -> {
                    notNull = true
                }
                constraints.accept("primary", "key") -> {
                    primaryKey = true
                    notNull = true
                }
                constraints.accept("default") -> {
                    hasDefault = true
                }
                constraints.accept("generated") -> {
                    hasDefault = true
                    // An identity column is never null; a generated (expression) column can be.
                    if (constraints.rest().any { it.lowercase() == "identity" }) {
                        notNull = true
                    }
                }
                else -> {
                    constraints.next()
                }
            }
        }

        return SqlColumn(name, type, notNull, primaryKey, hasDefault)
    }

    /** The type name of [parts] in lower case, without its length, precision or `USING` clause. */
    private fun typeName(parts: List<String>): String =
        parts
            .takeWhile { it.lowercase() != "using" && it.lowercase() !in COLUMN_CONSTRAINT }
            .filterNot { it.startsWith("(") }
            .joinToString(" ") { it.lowercase() }
            .replace(" [", "[")

    /** The columns of a `PRIMARY KEY (...)` table constraint, optionally named, or null. */
    private fun primaryKeyOf(parts: List<String>): List<String>? {
        val words = Words(parts)
        if (words.accept("constraint")) {
            words.next()
        }

        if (!words.accept("primary", "key")) {
            return null
        }

        val list = words.next()?.takeIf { it.startsWith("(") } ?: return null
        return split(list.substring(1, list.length - 1)).map { identifier(it.trim()) }
    }

    private fun Columns.markPrimaryKey(name: String) {
        val key = identifier(name)
        this[key]?.let { this[key] = it.copy(primaryKey = true, notNull = true) }
    }

    /**
     * The statements of [sql], split on `;` outside quotes, comments and dollar-quoted bodies; the
     * comments themselves are left out.
     */
    internal fun statements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < sql.length) {
            val char = sql[index]
            val end =
                when {
                    sql.startsWith("--", index) -> {
                        sql.indexOf('\n', index).let { if (it < 0) sql.length else it }
                    }
                    sql.startsWith("/*", index) -> {
                        sql.indexOf("*/", index + 2).let { if (it < 0) sql.length else it + 2 }
                    }
                    else -> {
                        -1
                    }
                }

            if (end >= 0) {
                current.append(' ')
                index = end
                continue
            }

            val quoted = quotedEnd(sql, index)
            when {
                quoted > index -> {
                    current.append(sql, index, quoted)
                    index = quoted
                }
                char == ';' -> {
                    statements += current.toString()
                    current.clear()
                    index++
                }
                else -> {
                    current.append(char)
                    index++
                }
            }
        }

        statements += current.toString()
        return statements.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * The tokens of one statement or definition: words, quoted strings and identifiers, and each
     * parenthesized group as one token, including its parentheses.
     */
    internal fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isWhitespace() -> {
                    index++
                }
                char == '(' -> {
                    val end = groupEnd(text, index)
                    tokens += text.substring(index, end)
                    index = end
                }
                char == ',' -> {
                    tokens += ","
                    index++
                }
                else -> {
                    val start = index
                    while (index < text.length) {
                        val c = text[index]
                        if (c.isWhitespace() || c == '(' || c == ',') {
                            break
                        }

                        val quoted = quotedEnd(text, index)
                        index = if (quoted > index) quoted else index + 1
                    }

                    tokens += text.substring(start, index)
                }
            }
        }

        return tokens
    }

    /** [text] split on commas outside parentheses and quotes. */
    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var index = 0
        while (index < text.length) {
            val quoted = quotedEnd(text, index)
            if (quoted > index) {
                current.append(text, index, quoted)
                index = quoted
                continue
            }

            val char = text[index]
            when (char) {
                '(' -> depth++
                ')' -> depth--
            }

            if (char == ',' && depth == 0) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(char)
            }

            index++
        }

        parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** The end of the parenthesized group that opens at [start]. */
    private fun groupEnd(
        text: String,
        start: Int,
    ): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            val quoted = quotedEnd(text, index)
            if (quoted > index) {
                index = quoted
                continue
            }

            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index + 1
            }

            index++
        }

        return text.length
    }

    /** The end of a quoted string, identifier or dollar-quoted body starting at [start], or [start]. */
    private fun quotedEnd(
        sql: String,
        start: Int,
    ): Int {
        val char = sql[start]
        if (char == '\'' || char == '"') {
            var index = start + 1
            while (index < sql.length) {
                if (sql[index] == char) {
                    // A doubled quote is an escaped quote.
                    if (index + 1 < sql.length && sql[index + 1] == char) {
                        index += 2
                        continue
                    }

                    return index + 1
                }

                index++
            }

            return sql.length
        }

        if (char == '$') {
            val tag = DOLLAR_TAG.find(sql.substring(start, minOf(sql.length, start + 64)))
            // The tag is anchored at the start: `$$` or `$name$`.
            if (tag != null) {
                val close = sql.indexOf(tag.value, start + tag.value.length)
                return if (close < 0) sql.length else close + tag.value.length
            }
        }

        return start
    }

    /** A table or column name in lower case, without schema and quotes. */
    private fun identifier(text: String): String =
        text
            .trim()
            .substringAfterLast('.')
            .trim('"')
            .lowercase()
}

/** A cursor over tokens, matching keywords case-insensitively. */
private class Words(
    private val tokens: List<String>,
) {
    private var index = 0

    val done: Boolean get() = index >= tokens.size

    fun peek(): String? = tokens.getOrNull(index)?.lowercase()

    fun next(): String? = tokens.getOrNull(index)?.also { index++ }

    fun rest(): List<String> = tokens.subList(minOf(index, tokens.size), tokens.size)

    /** Passes over [keywords] when they come next: an optional part of a statement. */
    fun skip(vararg keywords: String) {
        accept(*keywords)
    }

    /** Consumes [keywords] when the next tokens are exactly these, in order. */
    fun accept(vararg keywords: String): Boolean {
        val matches =
            keywords.withIndex().all { (offset, keyword) ->
                tokens.getOrNull(index + offset)?.lowercase() == keyword
            }

        if (matches) {
            index += keywords.size
        }

        return matches
    }
}
