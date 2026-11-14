package com.mariaray.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class XrayRunner(context: Context, private val workDir: File) {
    private val binPath: String = prepareBinary(context)

    private fun prepareBinary(context: Context): String {
        val src = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!src.exists()) {
            val alt = File(context.filesDir, "libxray.so")
            if (alt.exists()) return alt.absolutePath
            return src.absolutePath
        }
        val dst = File(context.filesDir, "xray")
        return try {
            if (!dst.exists() || dst.length() != src.length()) {
                src.copyTo(dst, overwrite = true)
            }
            dst.setExecutable(true, false)
            dst.absolutePath
        } catch (_: Exception) {
            src.absolutePath
        }
    }

    fun testHttp(link: String, port: Int, url: String, expected: Int): Boolean {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: return false
        val f = File(workDir, "c2_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return false
        return try {
            if (!waitPort(port)) return false
            httpCode(port, url) == expected
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

    private fun start(cfg: File): Process? = try {
        val p = ProcessBuilder(binPath, "run", "-c", cfg.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()
        FilterEngine.registerProc(p)
        p
    } catch (_: Exception) { null }

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
