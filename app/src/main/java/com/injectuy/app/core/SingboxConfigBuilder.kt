package com.injectuy.app.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.injectuy.app.parser.VmessBean

object SingboxConfigBuilder {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Generate JSON config untuk VMess (Direct/WS/TLS)
     */
    fun buildVmessConfig(vmess: VmessBean, localSocksPort: Int = 20808): String {
        val root = JsonObject()

        val log = JsonObject().apply {
            addProperty("level", "warn")
            addProperty("timestamp", false)
        }
        root.add("log", log)

        // Inbound TUN & SOCKS
        val inbounds = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                addProperty("inet4_address", "172.19.0.1/30")
                addProperty("auto_route", true)
                addProperty("strict_route", true)
                addProperty("stack", "system")
                addProperty("sniff", true)
            })
            add(JsonObject().apply {
                addProperty("type", "mixed")
                addProperty("tag", "mixed-in")
                addProperty("listen", "127.0.0.1")
                addProperty("listen_port", localSocksPort)
            })
        }
        root.add("inbounds", inbounds)

        // Outbound VMess
        val outbounds = JsonArray().apply {
            val vmessOut = JsonObject().apply {
                addProperty("type", "vmess")
                addProperty("tag", "proxy")
                addProperty("server", vmess.add)
                addProperty("server_port", vmess.port)
                addProperty("uuid", vmess.id)
                addProperty("security", if (vmess.scy.isEmpty()) "auto" else vmess.scy)
                addProperty("alter_id", vmess.aid)

                // TLS config
                if (vmess.tls.equals("tls", ignoreCase = true)) {
                    val tlsObj = JsonObject().apply {
                        addProperty("enabled", true)
                        val serverName = if (vmess.sni.isNotEmpty()) vmess.sni else vmess.host
                        if (serverName.isNotEmpty()) {
                            addProperty("server_name", serverName)
                        }
                        addProperty("insecure", true)
                    }
                    add("tls", tlsObj)
                }

                // Transport (WS/gRPC/HTTP)
                if (vmess.net.equals("ws", ignoreCase = true)) {
                    val transportObj = JsonObject().apply {
                        addProperty("type", "ws")
                        if (vmess.path.isNotEmpty()) addProperty("path", vmess.path)
                        if (vmess.host.isNotEmpty()) {
                            val headers = JsonObject().apply {
                                addProperty("Host", vmess.host)
                            }
                            add("headers", headers)
                        }
                    }
                    add("transport", transportObj)
                }
            }
            add(vmessOut)

            add(JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", "direct")
            })
            add(JsonObject().apply {
                addProperty("type", "dns")
                addProperty("tag", "dns-out")
            })
        }
        root.add("outbounds", outbounds)

        return gson.toJson(root)
    }

    /**
     * Generate JSON config untuk SSH Tunneling
     */
    fun buildSshConfig(
        serverHost: String,
        serverPort: Int,
        user: String,
        pass: String,
        localSocksPort: Int = 20808
    ): String {
        val root = JsonObject()

        val log = JsonObject().apply {
            addProperty("level", "warn")
        }
        root.add("log", log)

        val inbounds = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "tun")
                addProperty("tag", "tun-in")
                addProperty("interface_name", "tun0")
                addProperty("inet4_address", "172.19.0.1/30")
                addProperty("auto_route", true)
                addProperty("strict_route", true)
                addProperty("stack", "system")
            })
        }
        root.add("inbounds", inbounds)

        val outbounds = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "ssh")
                addProperty("tag", "proxy")
                addProperty("server", serverHost)
                addProperty("server_port", serverPort)
                addProperty("user", user)
                addProperty("password", pass)
            })
            add(JsonObject().apply {
                addProperty("type", "direct")
                addProperty("tag", "direct")
            })
        }
        root.add("outbounds", outbounds)

        return gson.toJson(root)
    }
}
