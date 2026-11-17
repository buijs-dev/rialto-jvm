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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SqlSchemaTest {
    private fun columns(
        sql: String,
        table: String = "t",
    ) = SqlSchema.read(sql).getValue(table).columns

    @Test
    fun `reads type names without length or precision, also of several words`() {
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
    fun `reads not null, primary keys, defaults and generated columns`() {
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
        val columns = columns(sql, "u")

        assertThat(SqlSchema.read(sql)).doesNotContainKey("t")
        assertThat(columns.keys).containsExactly("id", "a", "b", "c", "e", "g")
        assertThat(columns.getValue("a")).matches { it.type == "text" && it.notNull }

        assertThat(columns.getValue("b")).matches { it.type == "varchar" && it.acceptsNull }

        assertThat(columns.getValue("c").hasDefault).isFalse()
        assertThat(columns.getValue("e")).matches { it.notNull && it.hasDefault }

        assertThat(columns.getValue("g")).matches { it.name == "g" && it.type == "bigint" }
    }

    @Test
    fun `adds a primary key constraint and drops tables`() {
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

        assertThat(tables.keys).containsExactly("t")
        assertThat(
            tables
                .getValue("t")
                .columns
                .getValue("id")
                .primaryKey,
        ).isTrue()
    }

    @Test
    fun `skips comments, unknown statements and semicolons in strings and function bodies`() {
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

        assertThat(tables.keys).containsExactly("t")
        assertThat(tables.getValue("t").columns.keys).containsExactly("note", "mixed")
    }
}
