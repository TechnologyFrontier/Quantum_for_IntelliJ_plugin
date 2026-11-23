package com.technologyfrontier.qide.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class StrictJsonTest {
    @Test
    fun `parses nested values and keeps number text`() {
        val v = StrictJson.parse("""{"a":[1,-2.5e3,"xé\n"],"b":{"c":null,"d":true}, "big":18446744073709551615}""")
        assertIs<JsonObject>(v)
        val a = assertIs<JsonArray>(v["a"]).items
        assertEquals(JsonNumber("1"), a[0])
        assertEquals(JsonNumber("-2.5e3"), a[1])
        assertEquals(JsonString("xé\n"), a[2])
        assertEquals(JsonNumber("18446744073709551615"), v["big"])
        assertNull((v["big"] as JsonNumber).toLongExactOrNull(), "Long に収まらない値は null")
    }

    @Test
    fun `rejects duplicate keys`() {
        assertFailsWith<JsonParseException> { StrictJson.parse("""{"a":1,"a":2}""") }
    }

    @Test
    fun `rejects trailing data, comments and trailing commas`() {
        listOf("""{"a":1} x""", """{"a":1,}""", "[1,]", "// c\n{}", "{'a':1}", "01", "1.", "-", "\"\u0001\"", "").forEach {
            assertFailsWith<JsonParseException>(it) { StrictJson.parse(it) }
        }
    }

    @Test
    fun `integer token detection`() {
        assertEquals(5L, JsonNumber("5").toLongExactOrNull())
        assertNull(JsonNumber("5.0").toLongExactOrNull())
        assertNull(JsonNumber("5e0").toLongExactOrNull())
    }

    @Test
    fun `rejects excessive nesting`() {
        assertFailsWith<JsonParseException> { StrictJson.parse("[".repeat(100) + "]".repeat(100)) }
    }
}
