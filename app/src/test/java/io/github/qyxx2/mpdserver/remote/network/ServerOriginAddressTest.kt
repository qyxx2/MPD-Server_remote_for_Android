package io.github.qyxx2.mpdserver.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginAddressTest {
    @Test
    fun rawIpv4AndDomainDefaultToHttpPort18000() {
        assertEquals("http://192.168.1.20:18000", ServerOrigin.parseAddress("192.168.1.20").address)
        assertEquals("http://ms.example.com:18000", ServerOrigin.parseAddress("ms.example.com").address)
    }

    @Test
    fun rawAddressesAcceptExplicitPorts() {
        assertEquals("http://192.168.1.20:8000", ServerOrigin.parseAddress("192.168.1.20:8000").address)
        assertEquals("http://ms.example.com:18000", ServerOrigin.parseAddress("ms.example.com:18000").address)
    }

    @Test
    fun ipv6AcceptsBareAndBracketedForms() {
        assertEquals("http://[2001:db8::20]:18000", ServerOrigin.parseAddress("2001:db8::20").address)
        assertEquals("http://[2001:db8::20]:18000", ServerOrigin.parseAddress("[2001:db8::20]").address)
        assertEquals("http://[2001:db8::20]:8000", ServerOrigin.parseAddress("[2001:db8::20]:8000").address)
    }

    @Test
    fun explicitHttpAndHttpsOriginsPreserveSchemeAndPort() {
        assertEquals("http://192.168.1.20:18000", ServerOrigin.parseAddress("http://192.168.1.20:18000/").address)
        assertEquals("https://ms.example.com:18443", ServerOrigin.parseAddress("https://ms.example.com:18443").address)
        assertEquals("http://[2001:db8::20]:18000", ServerOrigin.parseAddress("http://[2001:db8::20]:18000").address)
    }

    @Test
    fun rejectsEmptyMalformedAndNonHttpInputs() {
        listOf("", "   ", "http://", "ms.example.com:abc", "ms.example.com:99999", "ftp://ms.example.com").forEach { input ->
            assertThrows("Should reject: $input", IllegalArgumentException::class.java) {
                ServerOrigin.parseAddress(input)
            }
        }
    }

    @Test
    fun rejectsCredentialsPathsQueriesAndFragments() {
        listOf(
            "http://user:pass@ms.example.com:18000",
            "http://ms.example.com:18000/api",
            "http://ms.example.com:18000?token=x",
            "http://ms.example.com:18000#fragment",
            "ms.example.com/api",
        ).forEach { input ->
            assertThrows("Should reject: $input", IllegalArgumentException::class.java) {
                ServerOrigin.parseAddress(input)
            }
        }
    }
}
