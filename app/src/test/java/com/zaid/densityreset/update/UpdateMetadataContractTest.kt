package com.zaid.densityreset.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMetadataContractTest {

    @Test
    fun sha256ContractAcceptsOnlyExactHexDigest() {
        val regex = Regex("^[0-9a-f]{64}$")
        assertTrue(regex.matches("a".repeat(64)))
        assertFalse(regex.matches("a".repeat(63)))
        assertFalse(regex.matches("g".repeat(64)))
        assertFalse(regex.matches("A".repeat(64)))
    }
}
