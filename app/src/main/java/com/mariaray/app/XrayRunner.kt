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
    private val candidates: List<File>

    init {
        val native = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        val filesCopy = File(context.filesDir, "xray")
        val cacheCopy = File(context.cacheDir, "xray")

        if (native.exists()) {
            try {
                if (!filesCopy.exists()) { native.copyTo(filesCopy, overwrite = true); filesCopy.setExecutable(true, false) }
            } catch (_: Exception) {}
            try {
                if (!cacheCopy.exists()) { native.copyTo(cacheCopy, overwrite = true); cacheCopy.setExecutable(true, false) }
            } catch (_: Exception) {}
        }

        val list = mutableListOf<File>()
        if (native.exists()) list.add(native)
        if (filesCopy.exists()) list.add(filesCopy)
        if (cacheCopy.exists()) list.add(cacheCopy)
        candidates = list.distinctBy { it.absolutePath }
    }

    fun binaryCandidates(): List<String> = candidates.map {
        "${it.absolutePath} (exec=${it.canExecute()}, size=${it.length()})"
    }

    fun execLogContent(): String = try {
        if (execLog.exists()) execLog.readText().lines().takeLast(20).joinToString("\n") else "(empty)"
    } catch (_: Exception) { "(read failed)" }

    fun testHttp(link: String, port: Int): Boolean {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: return false
        val f = File(workDir, "c2_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return false
        return try {
            if (!waitPort(port)) return false
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

    private fun start(cfg: File): Process? {
        for (c in candidates) {
            try {
                val p = ProcessBuilder(c.absolutePath, "run", "-c", cfg.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                FilterEngine.registerProc(p)
                return p
            } catch (e: Exception) {
                try {
                    execLog.appendText("${System.currentTimeMillis()} ${c.absolutePath}: ${e.javaClass.simpleName}: ${e.message}\n")
                } catch (_: Exception) {}
            }
        }
        return null
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
