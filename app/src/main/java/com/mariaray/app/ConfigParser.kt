package com.mariaray.app

import org.json.JSONObject
import java.net.URI

object ConfigParser {
    private const val DNS = "\"dns\":{\"servers\":[\"1.1.1.1\",\"8.8.8.8\",\"1.0.0.1\"]}"

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

    private fun vmessCfg(link: String): String? {
        val j = JSONObject(SubFetcher.decodeB64(link.removePrefix("vmess://")) ?: return null)
        val h = j.optString("add"); val p = j.optInt("port")
        val id = j.optString("id"); val aid = j.optInt("aid", 0)
        val net = j.optString("net", "tcp"); val tls = j.optString("tls")
        val hostH = j.optString("host"); val sni = j.optString("sni")
        val path = j.optString("path").ifEmpty { "/" }
        val stream = buildString {
            append(",\"streamSettings\":{\"network\":\"$net\"")
            if (net == "ws") append(",\"wsSettings\":{\"path\":\"$path\",\"headers\":{\"Host\":\"${hostH.ifEmpty { h }}\"}}")
            if (tls == "tls") append(",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"${sni.ifEmpty { hostH.ifEmpty { h } }}\"}")
            append("}")
        }
        return """{"protocol":"vmess","settings":{"vnext":[{"address":"$h","port":$p,"users":[{"id":"$id","alterId":$aid,"security":"auto"}]}]}$stream}"""
    }

    private fun trojanCfg(link: String): String? {
        val u = URI(link); val pw = u.userInfo ?: return null; val h = u.host ?: return null
        val p = if (u.port > 0) u.port else 443
        val sni = q(u.query, "sni") ?: h
        return """{"protocol":"trojan","settings":{"servers":[{"address":"$h","port":$p,"password":"$pw"}]},"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"$sni"}}}"""
    }

    private fun vlessCfg(link: String): String? {
        val u = URI(link); val uid = u.userInfo?.substringBefore(':') ?: return null
        val h = u.host ?: return null; val p = if (u.port > 0) u.port else 443
        val sni = q(u.query, "sni") ?: h; val net = q(u.query, "type") ?: "tcp"
        val path = q(u.query, "path") ?: "/"; val hostH = q(u.query, "host") ?: h
        val sec = q(u.query, "security") ?: "tls"
        val stream = buildString {
            append(",\"streamSettings\":{\"network\":\"$net\"")
            if (net == "ws") append(",\"wsSettings\":{\"path\":\"$path\",\"headers\":{\"Host\":\"$hostH\"}}")
            if (sec == "tls") append(",\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"$sni\"}")
            append("}")
        }
        return """{"protocol":"vless","settings":{"vnext":[{"address":"$h","port":$p,"users":[{"id":"$uid","encryption":"none"}]}]}$stream}"""
    }

    private fun q(qs: String?, key: String): String? {
        if (qs == null) return null
        return qs.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }?.get(1)
    }
}
