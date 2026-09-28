package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NbFunctionsPayloadTest {
    @Test
    fun `asStringMap normalizes callable map keys`() {
        val payload = mapOf<Any, Any?>("success" to true, 7 to "value")

        assertEquals(mapOf("success" to true, "7" to "value"), payload.asStringMap())
    }

    @Test
    fun `mapList unwraps named callable envelopes`() {
        val payload = mapOf<String, Any?>(
            "users" to listOf(
                mapOf("id" to "one"),
                "invalid",
                mapOf("id" to "two"),
            ),
        )

        assertEquals(listOf("one", "two"), payload.mapList("users").map { it["id"] })
    }

    @Test
    fun `mapList returns empty list for a missing field`() {
        assertTrue(emptyMap<String, Any?>().mapList("items").isEmpty())
    }
}
