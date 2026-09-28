package com.nextbench.app.verification

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class VerificationPhotoStoreTest {

    @Test
    fun missingExifDefaultsToNormalOrientation() {
        assertEquals(1, jpegOrientation(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))))
    }

    @Test
    fun parsesBigEndianExifOrientation() {
        assertEquals(6, jpegOrientation(ByteArrayInputStream(jpegWithOrientation(6, littleEndian = false))))
    }

    @Test
    fun parsesLittleEndianExifOrientation() {
        assertEquals(8, jpegOrientation(ByteArrayInputStream(jpegWithOrientation(8, littleEndian = true))))
    }

    private fun jpegWithOrientation(orientation: Int, littleEndian: Boolean): ByteArray {
        val tiff = if (littleEndian) {
            byteArrayOf(
                0x49, 0x49, 0x2A, 0x00,
                0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00,
                0x01, 0x00, 0x00, 0x00,
                orientation.toByte(), 0x00, 0x00, 0x00,
            )
        } else {
            byteArrayOf(
                0x4D, 0x4D, 0x00, 0x2A,
                0x00, 0x00, 0x00, 0x08,
                0x00, 0x01,
                0x01, 0x12, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x01,
                0x00, orientation.toByte(), 0x00, 0x00,
            )
        }
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff
        val segmentLength = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(),
            (segmentLength shr 8).toByte(), segmentLength.toByte(),
        ) + payload + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }
}
