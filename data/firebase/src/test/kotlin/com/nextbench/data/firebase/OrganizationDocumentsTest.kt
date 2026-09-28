package com.nextbench.data.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The organization registration document contract.
 *
 * A GSTIN / UDISE / registration certificate used to be uploaded through the unsigned
 * Cloudinary preset and stored as a public `orgDocumentUrl` on `users/{uid}` — a document
 * every signed-in, non-blocked user can read. These tests pin the replacement: bytes under
 * `organization/{uid}/...` in private Storage, referenced only by an owner-only pointer, with
 * no URL anywhere in the client contract.
 */
class OrganizationDocumentsTest {

    @Test
    fun `a document is stored under the organization's own private prefix`() {
        assertEquals(
            "organization/u1/document-sub1.jpg",
            organizationObjectPath("u1", "sub1", "image/jpeg"),
        )
        assertEquals(
            "organization/u1/document-sub1.pdf",
            organizationObjectPath("u1", "sub1", "application/pdf"),
        )
        assertEquals(
            "organization/u1/document-sub1.png",
            organizationObjectPath("u1", "sub1", "image/png"),
        )
        assertEquals(
            "organization/u1/document-sub1.webp",
            organizationObjectPath("u1", "sub1", "image/webp"),
        )
    }

    @Test
    fun `the extension comes from the resolved type, never from the file name`() {
        // The caller only passes a MIME type, so a hostile file name cannot introduce a path
        // segment, a traversal, or a key the storage rule would reject.
        val path = organizationObjectPath("u1", "sub1", "image/jpeg;../../other/evil")

        assertEquals("organization/u1/document-sub1.jpg", path)
        assertFalse(path.contains(".."))
        assertTrue(path.startsWith("organization/u1/"))
    }

    @Test
    fun `an unsupported or missing type is coerced to jpeg, never forwarded blindly`() {
        assertEquals("image/jpeg", organizationContentType(null))
        assertEquals("image/jpeg", organizationContentType("image/heic"))
        assertEquals("image/jpeg", organizationContentType("application/zip"))
        assertEquals("image/png", organizationContentType("IMAGE/PNG"))
        assertEquals("application/pdf", organizationContentType("application/pdf"))
        assertEquals("organization/u1/document-sub1.jpg", organizationObjectPath("u1", "sub1", null))
    }

    @Test
    fun `an account and a submission id are required`() {
        assertThrows(IllegalArgumentException::class.java) {
            organizationObjectPath("", "sub1", "image/jpeg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            organizationObjectPath("u1", "", "image/jpeg")
        }
    }

    @Test
    fun `each submission id is unique so a resubmission cannot overwrite in-flight evidence`() {
        val first = organizationSubmissionId()
        val second = organizationSubmissionId()

        assertFalse(first == second)
        assertTrue(first.startsWith("android_"))
        assertTrue(first.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun `the pointer carries only a private path — never a public URL or a server verdict`() {
        val payload = organizationPointerPayload(
            documentPath = "organization/u1/document-sub1.pdf",
            contentType = "application/pdf",
            submissionId = "sub1",
        )

        assertEquals(
            setOf("documentPath", "contentType", "submissionId", "updatedAt"),
            payload.keys,
        )
        listOf("orgDocumentUrl", "url", "verified", "orgVerified", "verificationStatus").forEach { forbidden ->
            assertFalse("$forbidden must not be client-written", payload.containsKey(forbidden))
        }
        val path = payload.getValue("documentPath").toString()
        assertTrue(path.startsWith("organization/"))
        assertFalse(path.contains("://"))
    }

    @Test
    fun `a confirmed replacement supersedes exactly the previous object`() {
        val previous = OrganizationDocumentSubmission(
            documentPath = "organization/u1/document-old.pdf",
            contentType = "application/pdf",
            submissionId = "old",
        )

        assertEquals(
            listOf("organization/u1/document-old.pdf"),
            supersededOrganizationObjects(previous, "new"),
        )
        // Re-writing the same submission must never delete the object it just wrote.
        assertTrue(supersededOrganizationObjects(previous, "old").isEmpty())
        assertTrue(supersededOrganizationObjects(null, "new").isEmpty())
    }

    @Test
    fun `the signup payload no longer carries a document URL at all`() {
        // Reflection, not a comment: the field is what put a registration certificate on the
        // publicly readable profile document. If it comes back, this fails.
        val fields = OrganizationSignupData::class.java.declaredFields.map { it.name }

        assertFalse(fields.contains("documentUrl"))
        assertFalse(fields.contains("orgDocumentUrl"))
        assertEquals(
            setOf("name", "type", "city", "website", "description", "referralCode"),
            fields.toSet(),
        )
    }
}
