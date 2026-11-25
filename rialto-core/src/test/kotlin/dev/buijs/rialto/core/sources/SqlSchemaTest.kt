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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlSchemaTest {
    private fun columns(
        sql: String,
        table: String = "t",
    ) = SqlSchema.read(sql).getValue(table).columns

    @Test
    fun `reads type names without length or precision, also of several words`() {
        // when
        val columns =
            columns(
                """
                CREATE TABLE public."T" (
                    a VARCHAR(40),
                    b character varying(10),
                    c DOUBLE PRECISION,
                    d numeric(8, 3),
                    e timestamp(3) with time zone,
                    f TEXT[],
                    g int COLLATE "C",
                    h VARCHAR(50)[]
                );
                """.trimIndent(),
            )

        // then
        assertThat(columns.mapValues { it.value.type }).containsExactlyEntriesOf(
            linkedMapOf(
                "a" to "varchar",
                "b" to "character varying",
                "c" to "double precision",
                "d" to "numeric",
                "e" to "timestamp with time zone",
                "f" to "text[]",
                "g" to "int",
                "h" to "varchar[]",
            ),
        )
    }

    @Test
    fun `a primary key constraint may stand before the columns it names`() {
        // when
        val columns = columns("CREATE TABLE t (PRIMARY KEY (id), id INT, name TEXT);")

        // then
        assertThat(columns.getValue("id")).matches { it.primaryKey && it.notNull }

        assertThat(columns.getValue("name")).matches { !it.primaryKey && !it.notNull }
    }

    @Test
    fun `reads not null, primary keys, defaults and generated columns`() {
        // when
        val columns =
            columns(
                """
                CREATE TABLE t (
                    id BIGINT GENERATED ALWAYS AS IDENTITY,
                    code TEXT NOT NULL DEFAULT 'x',
                    note TEXT NULL CHECK (note <> ''),
                    seq BIGSERIAL,
                    total INT GENERATED ALWAYS AS (1 + 1) STORED,
                    a INT,
                    b INT,
                    CONSTRAINT pk PRIMARY KEY (a, b)
                );
                """.trimIndent(),
            )

        // then
        assertThat(columns.getValue("id")).matches { it.notNull && it.hasDefault && !it.primaryKey }

        assertThat(columns.getValue("code")).matches { it.notNull && it.hasDefault }

        assertThat(columns.getValue("note")).matches { it.acceptsNull && !it.hasDefault }

        assertThat(columns.getValue("seq")).matches { it.notNull && it.hasDefault }

        assertThat(columns.getValue("total")).matches { it.acceptsNull && it.hasDefault }

        assertThat(columns.getValue("a")).matches { it.primaryKey && !it.acceptsNull }

        assertThat(columns.getValue("b").primaryKey).isTrue()
    }

    @Test
    fun `applies the alter table actions of later migrations in order (K1_9)`() {
        // given
        val sql =
            """
            CREATE TABLE t (id UUID PRIMARY KEY, a TEXT, b TEXT NOT NULL, c INT DEFAULT 1, d INT);
            ALTER TABLE t ADD COLUMN e TEXT NOT NULL, ADD f INT;
            ALTER TABLE IF EXISTS ONLY t ADD COLUMN IF NOT EXISTS a INT;
            ALTER TABLE t DROP COLUMN IF EXISTS d CASCADE;
            ALTER TABLE t RENAME COLUMN f TO g;
            ALTER TABLE t ALTER COLUMN a SET NOT NULL, ALTER b DROP NOT NULL;
            ALTER TABLE t ALTER COLUMN c DROP DEFAULT;
            ALTER TABLE t ALTER COLUMN e SET DEFAULT 'x';
            ALTER TABLE t ALTER COLUMN g TYPE BIGINT USING g::bigint;
            ALTER TABLE t ALTER COLUMN b SET DATA TYPE varchar(10);
            ALTER TABLE t ADD CONSTRAINT t_unique UNIQUE (a);
            ALTER TABLE t RENAME TO u;
            """.trimIndent()

        // when
        val tables = SqlSchema.read(sql)
        val columns = columns(sql, "u")

        // then
        assertThat(tables).doesNotContainKey("t")
        assertThat(columns.keys).containsExactly("id", "a", "b", "c", "e", "g")
        assertThat(columns.getValue("a")).matches { it.type == "text" && it.notNull }

        assertThat(columns.getValue("b")).matches { it.type == "varchar" && it.acceptsNull }

        assertThat(columns.getValue("c").hasDefault).isFalse()
        assertThat(columns.getValue("e")).matches { it.notNull && it.hasDefault }

        assertThat(columns.getValue("g")).matches { it.name == "g" && it.type == "bigint" }
    }

    @Test
    fun `adds a primary key constraint and drops tables`() {
        // when
        val tables =
            SqlSchema.read(
                """
                CREATE TABLE t (id UUID);
                ALTER TABLE t ADD CONSTRAINT t_pk PRIMARY KEY (id);
                CREATE TABLE a (id INT); CREATE TABLE b (id INT);
                DROP TABLE IF EXISTS a, b CASCADE;
                CREATE TABLE IF NOT EXISTS t (other INT);
                """.trimIndent(),
            )

        val id = tables.getValue("t").columns.getValue("id")

        // then
        assertThat(tables.keys).containsExactly("t")
        assertThat(id.primaryKey).isTrue()
    }

    @Test
    fun `skips comments, unknown statements and semicolons in strings and function bodies`() {
        // when
        val tables =
            SqlSchema.read(
                """
                -- a comment; with a semicolon
                /* CREATE TABLE ghost (id INT); */
                CREATE EXTENSION IF NOT EXISTS citext;
                CREATE FUNCTION touch() RETURNS trigger AS $$
                BEGIN NEW.at := now(); RETURN NEW; END;
                $$ LANGUAGE plpgsql;
                CREATE TABLE t (note TEXT DEFAULT 'a;b', "Mixed" INT);
                CREATE INDEX t_note ON t (note);
                INSERT INTO t (note) VALUES ('x; y');
                """.trimIndent(),
            )

        // then
        assertThat(tables.keys).containsExactly("t")
        assertThat(tables.getValue("t").columns.keys).containsExactly("note", "mixed")
    }

    @Test
    fun `reads temporary tables and leaves out statements that define no columns`() {
        // when
        val tables =
            SqlSchema.read(
                """
                CREATE GLOBAL TEMPORARY TABLE scratch (id INT);
                CREATE TABLE;
                CREATE TABLE nameless_body;
                CREATE TABLE copy AS SELECT * FROM scratch;
                CREATE TABLE IF NOT EXISTS fresh (id INT);
                CREATE TABLE odd (lonely, id INT, UNIQUE (id), PRIMARY KEY, PRIMARY KEY nope, PRIMARY KEY (nope));
                CREATE TABLE open (id INT
                """.trimIndent(),
            )
        val oddId = tables.getValue("odd").columns.getValue("id")

        // then
        assertThat(tables.keys).containsExactly("scratch", "fresh", "odd", "open")
        assertThat(tables.getValue("odd").columns.keys).containsExactly("id")
        assertThat(oddId.primaryKey).isFalse()
        assertThat(tables.getValue("open").columns.keys).containsExactly("id")
    }

    @Test
    fun `skips alter table actions it cannot apply`() {
        // given
        val sql =
            """
            CREATE TABLE t (id INT, a INT, b INT GENERATED BY DEFAULT AS IDENTITY, c INT);
            ALTER TABLE;
            ALTER TABLE t RENAME TO;
            ALTER TABLE ghost RENAME TO spirit;
            ALTER TABLE ghost ADD COLUMN x INT;
            ALTER TABLE t ADD;
            ALTER TABLE t ADD COLUMN lonely;
            ALTER TABLE t ADD COLUMN IF NOT EXISTS d INT;
            ALTER TABLE t ADD CONSTRAINT t_check CHECK (a > 0);
            ALTER TABLE t DROP CONSTRAINT t_check;
            ALTER TABLE t DROP COLUMN;
            ALTER TABLE t DROP;
            ALTER TABLE t RENAME CONSTRAINT x TO y;
            ALTER TABLE t RENAME COLUMN;
            ALTER TABLE t RENAME COLUMN a b;
            ALTER TABLE t RENAME COLUMN a TO;
            ALTER TABLE t RENAME COLUMN nope TO other;
            ALTER TABLE t OWNER TO admin;
            ALTER TABLE t ALTER COLUMN;
            ALTER TABLE t ALTER COLUMN nope SET NOT NULL;
            ALTER TABLE t ALTER COLUMN a SET STATISTICS 100;
            ALTER TABLE t ALTER COLUMN b DROP IDENTITY;
            ALTER TABLE t ALTER COLUMN c ADD GENERATED ALWAYS AS IDENTITY;
            ALTER TABLE t ALTER COLUMN a TYPE text COLLATE "C";
            """.trimIndent()

        // when
        val tables = SqlSchema.read(sql)
        val columns = tables.getValue("t").columns

        // then
        assertThat(tables.keys).containsExactly("t")
        assertThat(columns.keys).containsExactly("id", "a", "b", "c", "d")
        assertThat(columns.getValue("a").type).isEqualTo("text")
        assertThat(columns.getValue("b")).matches { !it.hasDefault }

        assertThat(columns.getValue("c")).matches { it.notNull && it.hasDefault }
    }

    @Test
    fun `reads quotes, comments and bodies that do not end, and doubled quotes`() {
        // when
        val statements =
            SqlSchema.statements(
                "CREATE TABLE t (note TEXT DEFAULT 'it''s; fine', p INT DEFAULT $1, " +
                    $$"q TEXT DEFAULT $x);\n" +
                    "CREATE TABLE u (id INT); -- a comment at the end",
            )

        // then
        assertThat(statements).hasSize(2)
        assertThat(SqlSchema.statements("CREATE TABLE t (id INT); /* never closed")).hasSize(1)
        assertThat(SqlSchema.statements("SELECT 'never closed; at all")).hasSize(1)
        assertThat(SqlSchema.statements($$"DO $body$ never closed; ")).hasSize(1)
        assertThat(SqlSchema.read("DROP TABLE a,,b;").keys).isEmpty()
    }

    @Test
    fun `a column accepts null unless it is not null or part of the primary key`() {
        // expect
        assertThat(SqlColumn("a", "int").acceptsNull).isTrue()
        assertThat(SqlColumn("a", "int", notNull = true).acceptsNull).isFalse()
        assertThat(SqlColumn("a", "int", primaryKey = true).acceptsNull).isFalse()
    }
}
