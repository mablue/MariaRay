package com.mariaray.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SubLinkResult(
    val url: String,
    val title: String,
    val source: String,
    val description: String,
    val stars: Int = 0
)

object SubLinkFinder {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val defaultGithubQueries = listOf(
        "v2ray subscription",
        "vmess vless trojan subscription",
        "free v2ray configs",
        "xray configs",
        "shadowsocks subscription"
    )

    private val defaultDdgQueries = listOf(
        "site:raw.githubusercontent.com vmess vless trojan",
        "site:github.com vmess vless subscription",
        "\"vmess://\" \"vless://\" site:github.com"
    )

    suspend fun search(customQuery: String): List<SubLinkResult> = coroutineScope {
        val ghQueries = if (customQuery.isBlank()) defaultGithubQueries else listOf(customQuery)
        val ddgQueries = if (customQuery.isBlank()) defaultDdgQueries
                         else listOf("$customQuery vmess vless")

        val ghDeferred = ghQueries.map { async(Dispatchers.IO) { searchGithub(it) } }
        val ddgDeferred = ddgQueries.map { async(Dispatchers.IO) { searchDDG(it) } }

        val gh = ghDeferred.flatMap { it.await() }
        val dd = ddgDeferred.flatMap { it.await() }

        (gh + dd).distinctBy { it.url }
    }

    private fun searchGithub(query: String): List<SubLinkResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://api.github.com/search/repositories?q=$q&sort=stars&per_page=15"
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MariaRay")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
                (0 until items.length()).mapNotNull { i ->
                    val o = items.getJSONObject(i)
                    val fullName = o.optString("full_name", "")
                    val htmlUrl = o.optString("html_url", "")
                    val desc = o.optString("description", "")
                    val stars = o.optInt("stargazers_count", 0)
                    if (htmlUrl.isBlank()) null
                    else SubLinkResult(htmlUrl, fullName.ifBlank { htmlUrl }, "gh", desc, stars)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun searchDDG(query: String): List<SubLinkResult> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val url = "https://html.duckduckgo.com/html/?q=$q"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val regex = Regex(
                    """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                    RegexOption.DOT_MATCHES_ALL
                )
                regex.findAll(body).mapNotNull { m ->
                    var raw = m.groupValues[1].trim()
                    val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    val real = when {
                        raw.startsWith("//duckduckgo.com/l/") && raw.contains("uddg=") -> {
                            try {
                                URLDecoder.decode(raw.substringAfter("uddg=").substringBefore("&"), "UTF-8")
                            } catch (_: Exception) { raw }
                        }
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("http") -> raw
                        else -> null
                    }
                    if (real == null) null
                    else SubLinkResult(real, title.ifBlank { real }, "ddg", "")
                }.toList()
            }
        } catch (_: Exception) { emptyList() }
    }
}
