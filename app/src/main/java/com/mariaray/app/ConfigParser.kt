package com.mariaray.app

import org.json.JSONObject
import java.net.URI

object ConfigParser {
    private val DNS: String by lazy {
        "\"dns\":{\"servers\":[" + Probes.DNS.joinToString(",") { "\"$it\"" } + "]}"
    }
    private const val DEFAULT_FP = "chrome"

    private fun ssBody(link: String): String =
        link.removePrefix("ss://").substringBefore("#").substringBefore("?").trimEnd('/')

    fun hostPort(link: String): Pair<String, Int>? = try {
        when {
            link.startsWith("ss://") -> ssHp(link)
            link.startsWith("vmess://") -> vmessHp(link)
            link.startsWith("vless://") || link.startsWith("trojan://") -> {
                val u = URI(link); val h = u.host ?: return null
                h to (if (u.port > 0) u.port else 443)
            }
            else -> null
        }
    } catch (_: Exception) { null }

    private fun ssHp(link: String): Pair<String, Int>? {
        val b = ssBody(link)
        val hp: String = if (!b.contains('@')) {
            val d = SubFetcher.decodeB64(b) ?: return null
            d.substringAfterLast('@', "")
        } else {
            b.substring(b.lastIndexOf('@') + 1)
        }
        if (hp.isBlank() || !hp.contains(':')) return null
        val h = hp.substringBeforeLast(':')
        val p = hp.substringAfterLast(':').toIntOrNull() ?: return null
        return if (h.isEmpty()) null else h to p
    }

    private fun vmessHp(link: String): Pair<String, Int>? {
        val j = JSONObject(SubFetcher.decodeB64(link.removePrefix("vmess://")) ?: return null)
        val h = j.optString("add"); val p = j.optInt("port")
        return if (h.isEmpty() || p == 0) null else h to p
    }

    fun toXrayConfig(link: String, socksPort: Int): String? = try {
        val body = when {
            link.startsWith("ss://") -> ssCfg(link)
            link.startsWith("vmess://") -> vmessCfg(link)
            link.startsWith("vless://") -> vlessCfg(link)
            link.startsWith("trojan://") -> trojanCfg(link)
            else -> return null
        }
        """{"log":{"loglevel":"error"},$DNS,"inbounds":[{"port":$socksPort,"listen":"127.0.0.1","protocol":"socks","settings":{"auth":"noauth","udp":false}}],"outbounds":[$body]}"""
    } catch (_: Exception) { null }

    private fun ssCfg(link: String): String? {
        val b = ssBody(link)
        var ui: String
        val hp: String
        if (!b.contains('@')) {
            val d = SubFetcher.decodeB64(b) ?: return null
            val at = d.lastIndexOf('@'); if (at < 0) return null
            ui = d.substring(0, at); hp = d.substring(at + 1)
        } else {
            val at = b.lastIndexOf('@'); ui = b.substring(0, at); hp = b.substring(at + 1)
            if (!ui.contains(':')) ui = SubFetcher.decodeB64(ui) ?: return null
        }
        if (!ui.contains(':') || !hp.contains(':')) return null
        val m = ui.substringBefore(':'); val pw = ui.substringAfter(':')
        val h = hp.substringBeforeLast(':'); val p = hp.substringAfterLast(':').toIntOrNull() ?: return null
        return """{"protocol":"shadowsocks","settings":{"servers":[{"address":"$h","port":$p,"method":"$m","password":"$pw"}]}}"""
    }

    // --- TLS fingerprint helpers (fixes critical #9) ---
    private fun buildTlsSettings(sni: String, fp: String?, alpn: String?, insecure: Boolean): String {
        val f = when {
            fp.isNullOrBlank() -> DEFAULT_FP
            else -> fp
        }
        val parts = mutableListOf<String>()
        parts += "\"serverName\":\"$sni\""
        parts += "\"fingerprint\":\"$f\""
        if (!alpn.isNullOrBlank()) {
            val al = alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (al.isNotEmpty()) {
                parts += "\"alpn\":[" + al.joinToString(",") { "\"$it\"" } + "]"
            }
        }
        if (insecure) parts += "\"allowInsecure\":true"
        return "{" + parts.joinToString(",") + "}"
    }

    private fun buildRealitySettings(sni: String, fp: String?, pbk: String?, sid: String?): String {
        val f = when {
            fp.isNullOrBlank() -> DEFAULT_FP
            else -> fp
        }
        val parts = mutableListOf<String>()
        parts += "\"serverName\":\"$sni\""
        parts += "\"fingerprint\":\"$f\""
        if (!pbk.isNullOrBlank()) parts += "\"publicKey\":\"$pbk\""
        if (!sid.isNullOrBlank()) parts += "\"shortId\":\"$sid\""
        return "{" + parts.joinToString(",") + "}"
    }

    private fun vmessCfg(link: String): String? {
        val j = JSONObject(SubFetcher.decodeB64(link.removePrefix("vmess://")) ?: return null)
        val h = j.optString("add"); val p = j.optInt("port")
        if (h.isEmpty() || p == 0) return null
        val id = j.optString("id"); val aid = j.optInt("aid", 0)
        val net = j.optString("net", "tcp"); val tls = j.optString("tls")
        val hostH = j.optString("host"); val sni = j.optString("sni")
        val path = j.optString("path").ifEmpty { "/" }
        val fp = j.optString("fp").ifBlank { j.optString("fingerprint") }
        val alpn = j.optString("alpn")
        val insecure = j.optString("allowInsecure") == "1" || j.optString("insecure") == "1"
        val stream = buildString {
            append(",\"streamSettings\":{\"network\":\"$net\"")
            if (net == "ws") append(",\"wsSettings\":{\"path\":\"$path\",\"headers\":{\"Host\":\"${hostH.ifEmpty { h }}\"}}")
            if (tls == "tls" || tls == "1" || tls == "true") {
                val sn = sni.ifEmpty { hostH.ifEmpty { h } }
                append(",\"security\":\"tls\",\"tlsSettings\":${buildTlsSettings(sn, fp, alpn, insecure)}")
            }
            append("}")
        }
        return """{"protocol":"vmess","settings":{"vnext":[{"address":"$h","port":$p,"users":[{"id":"$id","alterId":$aid,"security":"auto"}]}]}$stream}"""
    }

    private fun trojanCfg(link: String): String? {
        val u = URI(link); val pw = u.userInfo ?: return null; val h = u.host ?: return null
        val p = if (u.port > 0) u.port else 443
        val net = q(u.query, "type") ?: "tcp"
        val sni = q(u.query, "sni") ?: h
        val fp = q(u.query, "fp")
        val alpn = q(u.query, "alpn")
        val insecure = q(u.query, "allowInsecure") == "1" || q(u.query, "insecure") == "1"
        val path = q(u.query, "path") ?: "/"
        val hostH = q(u.query, "host") ?: h
        val stream = buildString {
            append(",\"streamSettings\":{\"network\":\"$net\"")
            if (net == "ws") append(",\"wsSettings\":{\"path\":\"$path\",\"headers\":{\"Host\":\"$hostH\"}}")
            append(",\"security\":\"tls\",\"tlsSettings\":${buildTlsSettings(sni, fp, alpn, insecure)}")
            append("}")
        }
        return """{"protocol":"trojan","settings":{"servers":[{"address":"$h","port":$p,"password":"$pw"}]}$stream}"""
    }

    private fun vlessCfg(link: String): String? {
        val u = URI(link); val uid = u.userInfo?.substringBefore(':') ?: return null
        val h = u.host ?: return null; val p = if (u.port > 0) u.port else 443
        val sni = q(u.query, "sni") ?: h
        val net = q(u.query, "type") ?: "tcp"
        val path = q(u.query, "path") ?: "/"
        val hostH = q(u.query, "host") ?: h
        val sec = q(u.query, "security") ?: "tls"
        val fp = q(u.query, "fp")
        val alpn = q(u.query, "alpn")
        val flow = q(u.query, "flow")
        val insecure = q(u.query, "allowInsecure") == "1" || q(u.query, "insecure") == "1"
        val pbk = q(u.query, "pbk")
        val sid = q(u.query, "sid")
        val userExtra = if (!flow.isNullOrBlank()) ",\"flow\":\"$flow\"" else ""
        val stream = buildString {
            append(",\"streamSettings\":{\"network\":\"$net\"")
            if (net == "ws") append(",\"wsSettings\":{\"path\":\"$path\",\"headers\":{\"Host\":\"$hostH\"}}")
            when (sec) {
                "tls", "1", "true" -> append(",\"security\":\"tls\",\"tlsSettings\":${buildTlsSettings(sni, fp, alpn, insecure)}")
                "reality" -> append(",\"security\":\"reality\",\"realitySettings\":${buildRealitySettings(sni, fp, pbk, sid)}")
            }
            append("}")
        }
        return """{"protocol":"vless","settings":{"vnext":[{"address":"$h","port":$p,"users":[{"id":"$uid","encryption":"none"$userExtra}]}]}$stream}"""
    }

    private fun q(qs: String?, key: String): String? {
        if (qs == null) return null
        return qs.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }?.get(1)
    }
}
