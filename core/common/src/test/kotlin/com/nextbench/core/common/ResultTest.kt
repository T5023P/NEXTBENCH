package com.nextbench.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {

    @Test
    fun `runCatchingNb returns Success on no exception`() {
        val result = runCatchingNb { 42 }
        assertTrue(result is NbResult.Success)
        assertEquals(42, (result as NbResult.Success).data)
    }

    @Test
    fun `runCatchingNb returns Failure on exception`() {
        val result = runCatchingNb<Int> { throw RuntimeException("PERMISSION_DENIED") }
        assertTrue(result is NbResult.Failure)
        assertEquals(NbError.PermissionDenied, (result as NbResult.Failure).error)
    }

    @Test
    fun `NbError fromException maps unknown to Unknown`() {
        assertEquals(NbError.Unknown, NbError.fromException(RuntimeException("oops")))
    }

    @Test
    fun `NbError fromException maps network error`() {
        assertEquals(NbError.Network, NbError.fromException(RuntimeException("UnknownHostException")))
    }
}
