package com.enhikyy.girassol.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeshSyncCryptoTest {
    @Test fun hmacSha256MatchesKnownVector() {
        val actual = MeshSyncLan.hmac("key".toByteArray(), "The quick brown fox jumps over the lazy dog")
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", actual)
    }

    @Test fun hmacSha256IsStableForSharedSecret() {
        val key = "local-pairing-secret".toByteArray()
        assertEquals(MeshSyncLan.hmac(key, "snapshot|128|abc"), MeshSyncLan.hmac(key, "snapshot|128|abc"))
    }

    @Test fun hmacSha256ChangesWhenSignedMetadataChanges() {
        val key = "local-pairing-secret".toByteArray()
        assertNotEquals(MeshSyncLan.hmac(key, "snapshot|128|abc"), MeshSyncLan.hmac(key, "snapshot|129|abc"))
    }
}
