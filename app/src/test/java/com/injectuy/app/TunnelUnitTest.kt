package com.injectuy.app

import com.injectuy.app.parser.PayloadParser
import com.injectuy.app.parser.VmessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelUnitTest {

    @Test
    fun testPayloadParser() {
        val rawPayload = "CONNECT [host_port] [protocol][crlf]Host: [host][crlf][crlf]"
        val parsed = PayloadParser.parse(rawPayload, "sg1.server.com", 80)
        
        assertEquals(
            "CONNECT sg1.server.com:80 HTTP/1.1\r\nHost: sg1.server.com\r\n\r\n",
            parsed
        )
    }

    @Test
    fun testVmessParser() {
        // Sample VMess JSON base64: {"add":"1.2.3.4","port":"443","id":"a-b-c-d","net":"ws","path":"/vmess","tls":"tls","ps":"TestServer"}
        val sampleVmess = "vmess://eyJhZGQiOiIxLjIuMy40IiwicG9ydCI6IjQ0MyIsImlkIjoiYS1iLWMtZCIsIm5ldCI6IndzIiwicGF0aCI6Ii92bWVzcyIsInRscyI6InRscyIsInBzIjoiVGVzdFNlcnZlciJ9"
        val bean = VmessParser.parse(sampleVmess)

        assertNotNull(bean)
        assertEquals("1.2.3.4", bean?.add)
        assertEquals(443, bean?.port)
        assertEquals("a-b-c-d", bean?.id)
        assertEquals("TestServer", bean?.ps)
        assertEquals("ws", bean?.net)
    }
}
