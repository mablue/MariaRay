package com.mariaray.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
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
        out += test("Xray SOCKS start") {
            val cfg = ConfigParser.toXrayConfig(sampleSs, 25003) ?: return@test "config null"
            val f = File(context.cacheDir, "dbg_xray.json").apply { writeText(cfg) }
            val p = try {
                ProcessBuilder(File(context.filesDir, "xray").absolutePath, "run", "-c", f.absolutePath)
                    .redirectErrorStream(true).start()
            } catch (e: Exception) { f.delete(); return@test "start failed: ${e.message}" }
            try {
                var ready = false
                for (i in 1..20) {
                    try {
                        Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", 25003), 100) }
                        ready = true; break
                    } catch (_: Exception) { Thread.sleep(50) }
                }
                "ready=$ready"
            } finally { p.destroyForcibly(); f.delete() }
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
