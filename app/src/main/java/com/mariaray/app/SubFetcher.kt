package com.mariaray.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

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
                isGithubRepoUrl(line) -> {
                    for (u in discoverGithubSubUrls(line, client)) {
                        try { out += parseSubscription(fetch(u, client)) } catch (_: Exception) {}
                    }
                }
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

    fun isGithubRepoUrl(url: String): Boolean {
        if (url.contains("raw.githubusercontent.com")) return false
        if (url.contains("gist.github.com")) return false
        if (url.contains("api.github.com")) return false
        return Regex("""^https?://github\.com/[^/]+/[^/?#]+/?(?:\?.*)?$""").matches(url)
    }

    fun discoverGithubSubUrls(repoUrl: String, client: OkHttpClient): List<String> {
        return try {
            val m = Regex("""github\.com/([^/]+)/([^/?#]+)""").find(repoUrl) ?: return emptyList()
            val user = m.groupValues[1]
            val repo = m.groupValues[2].removeSuffix(".git")

            var branch = "main"
            try {
                val infoReq = Request.Builder()
                    .url("https://api.github.com/repos/$user/$repo")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MariaRay")
                    .build()
                client.newCall(infoReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        branch = JSONObject(body).optString("default_branch", "main")
                    }
                }
            } catch (_: Exception) {}

            val treeReq = Request.Builder()
                .url("https://api.github.com/repos/$user/$repo/git/trees/$branch?recursive=1")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MariaRay")
                .build()
            val subs = mutableListOf<String>()
            client.newCall(treeReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val tree = JSONObject(body).optJSONArray("tree") ?: return emptyList()
                for (i in 0 until tree.length()) {
                    val item = tree.getJSONObject(i)
                    if (item.optString("type") != "blob") continue
                    val path = item.optString("path", "")
                    if (path.isBlank()) continue
                    if (!looksLikeSubFile(path)) continue
                    subs.add("https://raw.githubusercontent.com/$user/$repo/$branch/$path")
                }
            }
            subs.take(15)
        } catch (_: Exception) { emptyList() }
    }

    private fun looksLikeSubFile(path: String): Boolean {
        val lower = path.lowercase()
        if (!lower.endsWith(".txt") && !lower.endsWith(".json")) return false
        if (lower.contains("/.")) return false
        if (lower.contains("readme") || lower.contains("license") || lower.contains("package")) return false
        if (lower.contains("node_modules")) return false
        val tokens = listOf("sub", "config", "proxy", "v2ray", "vmess", "vless", "trojan", "shadowsocks", "xray", "all", "nodes", "free")
        if (tokens.any { lower.contains(it) }) return true
        return lower.count { it == '/' } <= 2 && lower.endsWith(".txt")
    }

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
