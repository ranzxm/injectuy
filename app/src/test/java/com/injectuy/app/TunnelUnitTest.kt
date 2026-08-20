package com.injectuy.app

import com.injectuy.app.parser.PayloadParser
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.parser.VmessParser
import com.injectuy.app.security.ConfigSecurity
import com.injectuy.app.security.EncryptedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelUnitTest {

    @Test
    fun testTargetParser() {
        val targetInput = "hi.xham.web.id:80@devdd117:117"
        val creds = TargetParser.parse(targetInput)

        assertEquals("hi.xham.web.id", creds.host)
        assertEquals(80, creds.port)
        assertEquals("devdd117", creds.user)
        assertEquals("117", creds.pass)
    }

    @Test
    fun testProxyParser() {
        val proxyInput = "104.17.70.206:80"
        val (host, port) = TargetParser.parseProxy(proxyInput)

        assertEquals("104.17.70.206", host)
        assertEquals(80, port)
    }

    @Test
    fun targetParserPreservesPasswordColonsAndValidatesPort() {
        val creds = TargetParser.parse("ssh.example:70000@user:p:ass")

        assertEquals("ssh.example", creds.host)
        assertEquals(22, creds.port)
        assertEquals("user", creds.user)
        assertEquals("p:ass", creds.pass)
    }

    @Test
    fun proxyParserSupportsBracketedIpv6() {
        val (host, port) = TargetParser.parseProxy("[2001:db8::1]:8443")

        assertEquals("2001:db8::1", host)
        assertEquals(8443, port)
    }

    @Test
    fun configExpiryIsEnforcedOnlyAfterExpiryTime() {
        val config = EncryptedConfig(expireDate = 1_000L)

        assertFalse(ConfigSecurity.isExpired(config, now = 1_000L))
        assertTrue(ConfigSecurity.isExpired(config, now = 1_001L))
    }

    @Test
    fun testPayloadParser() {
        val rawPayload = "CONNECT [host_port] [protocol][crlf]Host: [host][crlf][crlf]"
        val parsed = PayloadParser.parse(rawPayload, "sg1.server.com", 80)
        
        assertEquals(
            "CONNECT sg1.server.com:80 HTTP/1.1\r\nHost: sg1.server.com\r\n\r\n",
            parsed
        )
    }
}
