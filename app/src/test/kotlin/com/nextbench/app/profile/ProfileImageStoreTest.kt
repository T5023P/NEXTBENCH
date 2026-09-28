package com.nextbench.app.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileImageStoreTest {
    @Test
    fun `large sources are sampled before square cropping`() {
        assertEquals(1, profileSampleSize(1_080, 1_080))
        assertEquals(2, profileSampleSize(4_000, 3_000))
        assertEquals(4, profileSampleSize(8_000, 6_000))
    }
}
