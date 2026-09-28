package com.nextbench.data.firebase

import com.google.firebase.firestore.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRepositoryContractTest {

    @Test
    fun `block document id matches the identity the security rules assert`() {
        // rules: blockId == request.auth.uid + '_' + incoming().blockedId
        assertEquals("alice_bob", blockDocumentId("alice", "bob"))
        // Direction matters: the reverse block is a different document.
        assertEquals("bob_alice", blockDocumentId("bob", "alice"))
    }

    @Test
    fun `block payload carries only the fields the rules accept`() {
        val payload = blockPayload("alice", "bob")

        assertEquals(setOf("blockerId", "blockedId", "createdAt"), payload.keys)
        assertEquals("alice", payload["blockerId"])
        assertEquals("bob", payload["blockedId"])
    }

    @Test
    fun `block timestamp stays server owned`() {
        assertTrue(blockPayload("alice", "bob")["createdAt"] is FieldValue)
    }

    @Test
    fun `block removes the follow relationship in both directions`() {
        assertEquals(
            listOf("alice" to "bob", "bob" to "alice"),
            followRemovalPairs("alice", "bob"),
        )
    }

    @Test
    fun `the blocker's own follow is removed before the reverse follow`() {
        // Ordering is load-bearing: the reverse follow row is only deletable once
        // blocks/{blocker}_{blocked} exists, because the `follows` delete rule checks for it.
        val pairs = followRemovalPairs("alice", "bob")

        assertEquals("alice" to "bob", pairs.first())
        assertEquals("bob" to "alice", pairs.last())
        assertEquals(2, pairs.size)
    }

    @Test
    fun `the follow pairs are scoped to the blocked member only`() {
        val ids = followRemovalPairs("alice", "bob").flatMap { listOf(it.first, it.second) }.toSet()

        assertEquals(setOf("alice", "bob"), ids)
    }
}
