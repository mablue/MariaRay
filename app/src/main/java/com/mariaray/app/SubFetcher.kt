package com.mariaray.app

import okhttp3.OkHttpClient
import okhttp3.Request

object SubFetcher {
    val SCHEMES = listOf("ss://", "vmess://", "vless://", "trojan://")

    fun decodeB64(s: String): String? = try {
        var t = s.replace('-', '+').replace('_', '/')
            .replace("\n", "").replace("\r", "").replace(" ", "")
        while (t.length % 4 != 0) t += "="
        String(android.util.Base64.decode(t, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) { null }

    fun fetchAll(input: String, client: OkHttpClient): List<String> {
        val out = mutableListOf<String>()
        for (line in input.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                line.startsWith("http://") || line.startsWith("https://") -> {
                    try { out += parseSubscription(fetch(line, client)) } catch (_: Exception) {}
                }
                SCHEMES.any { line.startsWith(it) } -> out += line
                else -> {
                    val dec = decodeB64(line)
                    if (dec != null && SCHEMES.any { dec.contains(it) }) out += parseSubscription(dec)
                }
            }
        }
        return out.distinct()
    }

    fun parseSubscriptionText(body: String): List<String> = parseSubscription(body)

    private fun fetch(url: String, client: OkHttpClient): String {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { return it.body?.string() ?: "" }
    }

    private fun parseSubscription(body: String): List<String> {
        val t = body.trim()
        val lines = if (!t.contains("://")) (decodeB64(t) ?: t).lines() else t.lines()
        return lines.map { it.trim() }.filter { l -> SCHEMES.any { l.startsWith(it) } }
    }
}
