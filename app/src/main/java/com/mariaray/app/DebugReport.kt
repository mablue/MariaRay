package com.mariaray.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object DebugReport {
    data class TestResult(
        val name: String,
        val passed: Boolean,
        val detail: String,
        val elapsedMs: Long
    )

    private val sampleSs: String by lazy {
        "ss://" + android.util.Base64.encodeToString(
            "aes-256-gcm:testpass@example.com:8080".toByteArray(),
            android.util.Base64.NO_WRAP
        )
    }
    private val sampleVmess: String by lazy {
        val json = """{"v":"2","ps":"test","add":"1.1.1.1","port":"443","id":"00000000-0000-0000-0000-000000000000","aid":"0","net":"ws","type":"none","host":"","path":"/","tls":"tls"}"""
        "vmess://" + android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.NO_WRAP)
    }

    suspend fun runAll(context: Context): List<TestResult> {
        val out = mutableListOf<TestResult>()
        out += test("Device") {
            "${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }
        out += test("Xray path candidates") {
            fun d(f: File) = "path=${f.absolutePath}\nexists=${f.exists()} exec=${f.canExecute()} size=${f.length()}"
            val native = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            val filesCopy = File(context.filesDir, "xray")
            val cacheCopy = File(context.cacheDir, "xray")
            "nativeLibraryDir:\n${d(native)}\n\nfilesDir:\n${d(filesCopy)}\n\ncacheDir:\n${d(cacheCopy)}"
        }
        out += test("Xray exec per-path") {
            val cfg = File(context.cacheDir, "dbg_exec.json")
            cfg.writeText("""{"log":{"loglevel":"warning"},"inbounds":[],"outbounds":[{"protocol":"freedom"}]}""")
            val paths = listOf(
                File(context.applicationInfo.nativeLibraryDir, "libxray.so"),
                File(context.filesDir, "xray"),
                File(context.cacheDir, "xray")
            ).filter { it.exists() }
            if (paths.isEmpty()) { cfg.delete(); return@test "no candidates" }
            val sb = StringBuilder()
            for (c in paths) {
                try {
                    val p = ProcessBuilder(c.absolutePath, "version").redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().take(120).replace("\n", " | ")
                    p.waitFor()
                    sb.append("[OK] ${c.parentFile.name}/xray → $out").append('\n')
                } catch (e: Exception) {
                    sb.append("[FAIL] ${c.parentFile.name}/xray → ${e.javaClass.simpleName}: ${e.message}").append('\n')
                }
            }
            cfg.delete()
            sb.toString().trimEnd()
        }
        out += test("Exec error log") {
            val workDir = File(context.filesDir, "filter")
            val f = File(workDir, "exec_error.txt")
            if (!f.exists()) "(no exec errors — good sign or Stage 2 not run yet)" else f.readText().lines().take(10).joinToString("\n")
        }
        out += test("Xray binary") {
            val x = File(context.filesDir, "xray")
            "path=${x.absolutePath}\nexists=${x.exists()}\nsize=${x.length()}B\nexec=${x.canExecute()}"
        }
        out += test("Xray version") {
            val x = File(context.filesDir, "xray")
            if (!x.exists()) return@test "binary missing"
            val p = ProcessBuilder(x.absolutePath, "version").redirectErrorStream(true).start()
            val txt = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            txt.ifBlank { "(empty)" }
        }
        out += test("DNS 1.1.1.1") {
            "1.1.1.1 → ${InetAddress.getByName("1.1.1.1").hostAddress}"
        }
        out += test("DNS cp.cloudflare.com") {
            "cp.cloudflare.com → ${InetAddress.getByName("cp.cloudflare.com").hostAddress}"
        }
        out += test("TCP cp.cloudflare.com:80") {
            Socket().use { it.connect(InetSocketAddress("cp.cloudflare.com", 80), 3000) }
            "connected"
        }
        out += test("TCP cp.cloudflare.com:443") {
            Socket().use { it.connect(InetSocketAddress("cp.cloudflare.com", 443), 3000) }
            "connected"
        }
        out += test("HTTP probes (direct)") {
            val c = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
            val sb = StringBuilder()
            for ((url, expected) in Probes.HTTP) {
                val code = try {
                    c.newCall(Request.Builder().url(url).build()).execute().use { it.code }
                } catch (_: Exception) { 0 }
                val ok = code == expected || code in 200..299
                sb.append(if (ok) "[OK] " else "[--] ").append("$code  $url").append('\n')
            }
            sb.toString().trimEnd()
        }
        out += test("DNS fallback list") {
            val sb = StringBuilder()
            for (d in Probes.DNS) {
                val r = try { InetAddress.getByName(d).hostAddress ?: "?" }
                        catch (_: Exception) { "FAIL" }
                sb.append("$d → $r").append('\n')
            }
            sb.toString().trimEnd()
        }
        out += test("HTTP 204 (direct)") {
            val c = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
            val code = c.newCall(
                Request.Builder().url("http://cp.cloudflare.com/generate_204").build()
            ).execute().use { it.code }
            "code=$code"
        }
        out += test("Base64 decode") {
            val d = SubFetcher.decodeB64("YWVzLTI1Ni1nY206cGFzcw==") ?: return@test "null"
            "decoded='$d'"
        }
        out += test("Parse SS") {
            "hostPort=${ConfigParser.hostPort(sampleSs)}"
        }
        out += test("Parse VMess") {
            "hostPort=${ConfigParser.hostPort(sampleVmess)}"
        }
        out += test("Gen Xray SS") {
            val c = ConfigParser.toXrayConfig(sampleSs, 25001)
            if (c == null) "null" else "${c.length} chars"
        }
        out += test("Gen Xray VMess") {
            val c = ConfigParser.toXrayConfig(sampleVmess, 25002)
            if (c == null) "null" else "${c.length} chars"
        }
        out += test("VLESS+fp JSON valid") {
            val sample = "vless://00000000-0000-0000-0000-000000000000@1.1.1.1:443?security=tls&sni=example.com&type=tcp&fp=chrome#test"
            val c = ConfigParser.toXrayConfig(sample, 25004) ?: return@test "null"
            try {
                val o = JSONObject(c)
                val ob = o.getJSONArray("outbounds").getJSONObject(0)
                val ss = ob.optJSONObject("streamSettings") ?: return@test "no streamSettings"
                val tls = ss.optJSONObject("tlsSettings") ?: return@test "no tlsSettings"
                val fp = tls.optString("fingerprint")
                if (fp != "chrome") return@test "fp=$fp (want chrome)"
                "ok"
            } catch (e: Exception) { "invalid JSON: ${e.message}" }
        }
        out += test("VLESS+reality JSON valid") {
            val sample = "vless://00000000-0000-0000-0000-000000000000@1.1.1.1:443?security=reality&sni=example.com&fp=chrome&pbk=abc123&sid=0123abcd&type=tcp&flow=xtls-rprx-vision#test"
            val c = ConfigParser.toXrayConfig(sample, 25005) ?: return@test "null"
            try {
                val o = JSONObject(c)
                val ob = o.getJSONArray("outbounds").getJSONObject(0)
                val ss = ob.optJSONObject("streamSettings") ?: return@test "no streamSettings"
                if (ss.optString("security") != "reality") return@test "sec=${ss.optString("security")}"
                val rs = ss.optJSONObject("realitySettings") ?: return@test "no realitySettings"
                if (rs.optString("publicKey") != "abc123") return@test "pbk=${rs.optString("publicKey")}"
                if (rs.optString("shortId") != "0123abcd") return@test "sid=${rs.optString("shortId")}"
                val user = ob.getJSONObject("settings").getJSONArray("vnext")
                    .getJSONObject(0).getJSONArray("users").getJSONObject(0)
                if (user.optString("flow") != "xtls-rprx-vision") return@test "flow=${user.optString("flow")}"
                "ok"
            } catch (e: Exception) { "invalid JSON: ${e.message}" }
        }
        out += test("VLESS+ws+fp JSON valid") {
            val sample = "vless://00000000-0000-0000-0000-000000000000@1.1.1.1:443?security=tls&sni=example.com&type=ws&path=%2Fws&host=example.com&fp=firefox#test"
            val c = ConfigParser.toXrayConfig(sample, 25006) ?: return@test "null"
            try {
                val o = JSONObject(c)
                val ob = o.getJSONArray("outbounds").getJSONObject(0)
                val ss = ob.optJSONObject("streamSettings") ?: return@test "no streamSettings"
                if (ss.optString("network") != "ws") return@test "net=${ss.optString("network")}"
                val tls = ss.optJSONObject("tlsSettings") ?: return@test "no tlsSettings"
                if (tls.optString("fingerprint") != "firefox") return@test "fp=${tls.optString("fingerprint")}"
                "ok"
            } catch (e: Exception) { "invalid JSON: ${e.message}" }
        }
        out += test("Default fp applied (no fp in link)") {
            val sample = "vless://00000000-0000-0000-0000-000000000000@1.1.1.1:443?security=tls&sni=example.com&type=tcp#test"
            val c = ConfigParser.toXrayConfig(sample, 25007) ?: return@test "null"
            val o = JSONObject(c)
            val ob = o.getJSONArray("outbounds").getJSONObject(0)
            val tls = ob.getJSONObject("streamSettings").optJSONObject("tlsSettings") ?: return@test "no tlsSettings"
            "fp=${tls.optString("fingerprint")}"
        }
        out += test("Xray SOCKS start") {
            val cfg = ConfigParser.toXrayConfig(sampleSs, 25003) ?: throw Exception("config null")
            val f = File(context.cacheDir, "dbg_xray.json").apply { writeText(cfg) }
            val runner = XrayRunner(context, File(context.filesDir, "filter").apply { mkdirs() })
            val candidatePaths = runner.binaryCandidates()
            if (candidatePaths.isEmpty()) { f.delete(); throw Exception("no xray binary candidates") }
            var p: Process? = null
            var lastErr = ""
            for (cand in candidatePaths) {
                val path = cand.substringBefore(" (")
                try {
                    p = ProcessBuilder(path, "run", "-c", f.absolutePath)
                        .redirectErrorStream(true).start()
                    break
                } catch (e: Exception) { lastErr = "$path -> ${e.message}" }
            }
            if (p == null) { f.delete(); throw Exception("all candidates failed: $lastErr") }
            try {
                var ready = false
                for (i in 1..30) {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 25003), 100) }
                        ready = true; break
                    } catch (_: Exception) { Thread.sleep(50) }
                }
                if (!ready) throw Exception("process started but SOCKS port not ready in 1.5s")
                "ready=true (via ${p.javaClass.simpleName})"
            } finally { p.destroyForcibly(); f.delete() }
        }
        out += test("GitHub repo URL detection") {
            val a = SubFetcher.isGithubRepoUrl("https://github.com/MhdiTaheri/V2rayCollector")
            val b = SubFetcher.isGithubRepoUrl("https://raw.githubusercontent.com/x/y/sub.txt")
            val c = SubFetcher.isGithubRepoUrl("https://github.com/user/repo/tree/main/data")
            "repo=$a, raw=$b, subpath=$c"
        }
        out += test("GitHub repo discovery") {
            val cl = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            val subs = SubFetcher.discoverGithubSubUrls("https://github.com/MhdiTaheri/V2rayCollector", cl)
            if (subs.isEmpty()) "no sub files found"
            else "${subs.size} candidates:\n" + subs.take(5).joinToString("\n") { "  - $it" }
        }
        out += test("Fetch sub URL") {
            val url = "https://raw.githubusercontent.com/v2FreeHub/v2hub-configs/refs/heads/main/Sub-AutoUpdate"
            val c = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            val body = c.newCall(
                Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            ).execute().use { it.body?.string() ?: "" }
            val configs = SubFetcher.parseSubscriptionText(body)
            "body=${body.length}B, configs=${configs.size}"
        }
        out += test("Xray exec log") {
            val runner = XrayRunner(context, File(context.filesDir, "filter").apply { mkdirs() })
            runner.execLogContent()
        }
        return out
    }

    private suspend fun test(name: String, block: suspend () -> String): TestResult {
        val t0 = System.currentTimeMillis()
        return try {
            val detail = withTimeout(15_000) { withContext(Dispatchers.IO) { block() } }
            TestResult(name, true, detail, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            TestResult(name, false, "EXC ${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - t0)
        }
    }

    fun format(results: List<TestResult>): String = buildString {
        appendLine("════════ MariaRay Debug Report ════════")
        appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("──────────────────────────────────────")
        for (r in results) {
            appendLine("${if (r.passed) "[OK]  " else "[FAIL]"} ${r.name} (${r.elapsedMs}ms)")
            for (line in r.detail.lines()) appendLine("       $line")
        }
        appendLine("──────────────────────────────────────")
        appendLine("Passed: ${results.count { it.passed }}/${results.size}")
    }
}
