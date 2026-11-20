package com.mariaray.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class XrayRunner(private val context: Context, private val workDir: File) {
    private val execLog = File(workDir, "exec_log.txt")
    private val candidates: List<String>

    init {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val native = File(nativeDir, "libxray.so")
        val filesCopy = File(context.filesDir, "xray")
        val cacheCopy = File(context.cacheDir, "xray")
        val noBackup = File(context.noBackupFilesDir, "xray")

        // Ensure copies exist with exec bit (harmless if already there)
        if (native.exists()) {
            for (dst in listOf(filesCopy, cacheCopy, noBackup)) {
                try {
                    if (!dst.exists() || dst.length() != native.length()) {
                        native.copyTo(dst, overwrite = true)
                    }
                    dst.setExecutable(true, false)
                    dst.setReadable(true, true)
                } catch (_: Exception) {}
            }
        }

        // Order: nativeLibraryDir first (SELinux-allowed exec path)
        val list = mutableListOf<String>()
        if (native.exists()) list.add(native.absolutePath)
        if (noBackup.exists()) list.add(noBackup.absolutePath)
        if (filesCopy.exists()) list.add(filesCopy.absolutePath)
        if (cacheCopy.exists()) list.add(cacheCopy.absolutePath)
        candidates = list.distinct()
    }

    fun binaryCandidates(): List<String> = candidates.map { p ->
        val f = File(p)
        "$p (exec=${f.canExecute()}, size=${f.length()})"
    }

    fun execLogContent(): String = try {
        if (execLog.exists()) execLog.readText().lines().takeLast(20).joinToString("\n") else "(empty)"
    } catch (_: Exception) { "(read failed)" }

    fun testHttp(link: String, port: Int): Boolean {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: run {
            log("cfg-null", "toXrayConfig returned null for ${link.take(60)}")
            return false
        }
        val f = File(workDir, "c2_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return false
        return try {
            if (!waitPort(port)) {
                log("wait-port", "port=$port not ready in 1500ms (pid=${p.pid()})")
                return false
            }
            for ((url, expected) in Probes.HTTP) {
                val code = httpCode(port, url)
                if (code == expected || code in 200..299) return true
            }
            false
        } finally {
            try { p.destroyForcibly() } catch (_: Exception) {}
            FilterEngine.unregisterProc(p)
            f.delete()
        }
    }

    fun testSpeedKbps(link: String, port: Int): Double {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: return 0.0
        val f = File(workDir, "c3_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return 0.0
        return try {
            if (!waitPort(port)) 0.0 else downloadKbps(port)
        } finally {
            try { p.destroyForcibly() } catch (_: Exception) {}
            FilterEngine.unregisterProc(p)
            f.delete()
        }
    }

    fun tryExecOnce(): String {
        val cfg = File(workDir, "probe_exec.json").apply {
            writeText("""{"log":{"loglevel":"warning"},"inbounds":[],"outbounds":[{"protocol":"freedom"}]}""")
        }
        try {
            for (c in candidates) {
                val result = try {
                    val p = ProcessBuilder(c, "version")
                        .redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor()
                    "OK via $c → ${out.lineSequence().firstOrNull() ?: "(no output)"}"
                } catch (e: Exception) {
                    "FAIL $c → ${e.javaClass.simpleName}: ${e.message}"
                }
                // Log first success and first failure, continue anyway
                log("try-exec", result)
                if (result.startsWith("OK")) return result
            }
            "ALL FAILED — see exec_log.txt"
        } finally { cfg.delete() }
    }

    private fun start(cfg: File): Process? {
        for (path in candidates) {
            try {
                val p = ProcessBuilder(path, "run", "-c", cfg.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                FilterEngine.registerProc(p)
                return p
            } catch (e: Exception) {
                log("start", "$path → ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    private fun log(tag: String, msg: String) {
        try {
            execLog.appendText("${System.currentTimeMillis()} [$tag] $msg\n")
        } catch (_: Exception) {}
    }

    private fun waitPort(port: Int, t: Int = 1500): Boolean {
        val s = System.currentTimeMillis()
        while (System.currentTimeMillis() - s < t) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; return true }
            catch (_: Exception) { Thread.sleep(40) }
        }
        return false
    }

    private fun httpCode(port: Int, url: String): Int {
        val c = clientFor(port, 4)
        return try { c.newCall(Request.Builder().url(url).build()).execute().use { it.code } }
        catch (_: Exception) { 0 }
    }

    private fun downloadKbps(port: Int): Double {
        val c = clientFor(port, 8)
        return try {
            val req = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=200000").build()
            val t0 = System.currentTimeMillis()
            val n = c.newCall(req).execute().use { it.body?.bytes()?.size ?: 0 }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            if (sec > 0) (n / 1024.0) / sec else 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun clientFor(port: Int, timeoutSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
}
