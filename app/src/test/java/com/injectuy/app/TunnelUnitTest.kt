package com.injectuy.app

import com.injectuy.app.parser.PayloadParser
import com.injectuy.app.parser.TargetParser
import com.injectuy.app.parser.VmessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun testPayloadParser() {
        val rawPayload = "CONNECT [host_port] [protocol][crlf]Host: [host][crlf][crlf]"
        val parsed = PayloadParser.parse(rawPayload, "sg1.server.com", 80)
        
        assertEquals(
            "CONNECT sg1.server.com:80 HTTP/1.1\r\nHost: sg1.server.com\r\n\r\n",
            parsed
        )
    }
}
