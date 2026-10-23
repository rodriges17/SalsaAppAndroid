package com.stepbystep.salsa

import org.junit.Assert.*
import org.junit.Test

class IncomingLinkTest {
    private val pages = "https://rodriges17.github.io/SalsaAppCloud/"
    private val auth = "https://tzaprobvauikssnpiucl.supabase.co/auth/v1/verify"
    private val token = "valid-token-hash_1234567890"

    @Test fun trustedLibraryLinksDecodeTheFirstIdentifierAndAllowCloudHome() {
        assertEquals(IncomingLink.Library("Größe 1"), IncomingLink.classify("$pages?library=Gr%C3%B6%C3%9Fe+1&library=second"))
        assertEquals(IncomingLink.Library(null), IncomingLink.classify(pages))
        assertEquals(IncomingLink.Library(""), IncomingLink.classify("$pages?library="))
        assertEquals(IncomingLink.Library("abc"), IncomingLink.classify("Geteilt: <$pages?library=abc>."))
    }

    @Test fun trustedLoginLinksUseBothExistingSourcesAndDoNotFallBackToLibrary() {
        assertEquals(IncomingLink.Login(token), IncomingLink.classify("$pages?token_hash=$token&type=magiclink"))
        assertEquals(IncomingLink.Login(token), IncomingLink.classify("$auth?token_hash=$token&type=magiclink"))
        assertNull(IncomingLink.classify("$pages?token_hash=short&type=magiclink&library=abc"))
        assertNull(IncomingLink.classify("$auth?library=abc"))
    }

    @Test fun loginTokenAndTypeValidationRemainBounded() {
        assertEquals(IncomingLink.Login("a".repeat(16)), IncomingLink.classify("$auth?token_hash=${"a".repeat(16)}&type=magiclink"))
        assertEquals(IncomingLink.Login("a".repeat(512)), IncomingLink.classify("$auth?token_hash=${"a".repeat(512)}&type=magiclink"))
        for (value in listOf("", "a".repeat(15), "a".repeat(513), "abc%20defghijklmnop", "abc%2Fdefghijklmnop")) {
            assertNull(IncomingLink.classify("$auth?token_hash=$value&type=magiclink"))
        }
        for (type in listOf("", "recovery", "MagicLink")) {
            assertNull(IncomingLink.classify("$auth?token_hash=$token&type=$type"))
        }
    }

    @Test fun foreignOriginsWrongPathsAndMalformedLinksAreRejected() {
        for (raw in listOf(
            "http://rodriges17.github.io/SalsaAppCloud/?library=abc",
            "https://rodriges17.github.io.evil.test/SalsaAppCloud/?library=abc",
            "https://rodriges17.github.io/SalsaAppCloudOther/?library=abc",
            "https://rodriges17.github.io/SalsaAppIOS/?library=abc",
            "https://tzaprobvauikssnpiucl.supabase.co/storage/v1/?token_hash=$token&type=magiclink",
            "$pages?library=bad%escape",
            "No link here",
        )) assertNull(raw, IncomingLink.classify(raw))
    }
}
