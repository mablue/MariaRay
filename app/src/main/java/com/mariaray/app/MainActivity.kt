package com.mariaray.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mariaray.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var lastBest: String = ""
    private val logBuffer = StringBuilder()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FilterService.BROADCAST_PROGRESS -> {
                    val stage = intent.getStringExtra(FilterService.EXTRA_STAGE) ?: ""
                    val cur = intent.getIntExtra(FilterService.EXTRA_CUR, 0)
                    val total = intent.getIntExtra(FilterService.EXTRA_TOTAL, 0)
                    val msg = intent.getStringExtra(FilterService.EXTRA_MSG) ?: ""
                    if (total > 0) binding.progressBar.progress = (cur * 100 / total)
                    binding.tvStatus.text = "$stage: $cur/$total"
                    appendLog("$stage: $cur/$total — $msg")
                }
                FilterService.BROADCAST_DONE -> {
                    val err = intent.getStringExtra(FilterService.EXTRA_ERROR)
                    if (err != null) {
                        binding.tvStatus.text = "Error: $err"
                        appendLog("DONE with error: $err")
                    } else {
                        val path = intent.getStringExtra(FilterService.EXTRA_RESULT_PATH)
                        if (path != null) {
                            val f = File(path)
                            if (f.exists()) {
                                lastBest = f.readText()
                                val n = lastBest.lines().count { it.isNotBlank() }
                                binding.tvStatus.text = "Done: $n configs"
                                binding.progressBar.progress = 100
                                appendLog("DONE: $n configs kept")
                            }
                        }
                    }
                    binding.btnFilter.isEnabled = true
                    binding.btnStop.isEnabled = false
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etInput.setText(
            "https://raw.githubusercontent.com/v2FreeHub/v2hub-configs/refs/heads/main/Sub-AutoUpdate"
        )

        binding.btnFilter.setOnClickListener { startFilter() }
        binding.btnStop.setOnClickListener {
            val i = Intent(this, FilterService::class.java).apply { action = FilterService.ACTION_STOP }
            startService(i)
            binding.tvStatus.text = "Stopping..."
            appendLog("Stop requested")
        }
        binding.btnCopy.setOnClickListener {
            if (lastBest.isEmpty()) { toast("Nothing to copy"); return@setOnClickListener }
            copyToClipboard(lastBest, "Copied ${lastBest.lines().count { it.isNotBlank() }} configs")
        }
        binding.btnDebug.setOnClickListener { runDebug() }
        binding.btnSearch.setOnClickListener { runSearch() }

        // Restore previous result if present
        val best = File(filesDir, "best.txt")
        if (best.exists()) {
            lastBest = best.readText()
            val n = lastBest.lines().count { it.isNotBlank() }
            if (n > 0) appendLog("Loaded previous: $n configs")
        }

        requestNotifPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(FilterService.BROADCAST_PROGRESS)
            addAction(FilterService.BROADCAST_DONE)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun startFilter() {
        val input = binding.etInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) { toast("Enter URL or paste configs"); return }

        logBuffer.setLength(0)
        binding.tvOutput.text = ""
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting..."
        binding.btnFilter.isEnabled = false
        binding.btnStop.isEnabled = true
        appendLog("Starting filter with ${input.length} chars input")

        val intent = Intent(this, FilterService::class.java).apply {
            action = FilterService.ACTION_START
            putExtra(FilterService.EXTRA_INPUT, input)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            appendLog("ERROR: Cannot start service: ${e.message}")
            binding.btnFilter.isEnabled = true
            binding.btnStop.isEnabled = false
        }
    }

    private fun runDebug() {
        binding.btnDebug.isEnabled = false
        binding.tvStatus.text = "Running debug tests..."
        appendLog("Debug: starting tests")
        lifecycleScope.launch {
            try {
                val results = DebugReport.runAll(this@MainActivity)
                val report = DebugReport.format(results)
                appendLog("Debug: done — ${results.count { it.passed }}/${results.size} passed")
                showReportDialog(report)
            } catch (e: Exception) {
                appendLog("Debug failed: ${e.message}")
                toast("Debug failed: ${e.message}")
            } finally {
                binding.btnDebug.isEnabled = true
                binding.tvStatus.text = "Ready"
            }
        }
    }

    private fun runSearch() {
        val q = binding.etSearch.text?.toString()?.trim().orEmpty()
        binding.btnSearch.isEnabled = false
        binding.tvStatus.text = "Searching..."
        appendLog("Search: query='${q.ifBlank { "(defaults)" }}'")
        lifecycleScope.launch {
            try {
                val results = SubLinkFinder.search(q)
                appendLog("Search: ${results.size} results")
                if (results.isEmpty()) {
                    toast("No results found")
                    return@launch
                }
                showSearchResults(results)
            } catch (e: Exception) {
                appendLog("Search failed: ${e.message}")
                toast("Search failed: ${e.message}")
            } finally {
                binding.btnSearch.isEnabled = true
                binding.tvStatus.text = "Ready"
            }
        }
    }

    private fun showSearchResults(results: List<SubLinkResult>) {
        val display = results.map { r ->
            val stars = if (r.stars > 0) " ⭐${r.stars}" else ""
            "[${r.source}]$stars ${r.title}\n${r.url}"
        }
        val items: Array<CharSequence> = Array<CharSequence>(display.size) { display[it] }
        AlertDialog.Builder(this)
            .setTitle("${results.size} results")
            .setItems(items) { _, which ->
                val chosen = results[which]
                binding.etInput.setText(chosen.url)
                toast("URL loaded. Press Filter.")
                appendLog("Selected: ${chosen.url}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showReportDialog(report: String) {
        val tv = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            textSize = 10f
            setPadding(32, 16, 32, 16)
        }
        val sv = ScrollView(this).apply {
            addView(tv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Debug Report")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ -> copyToClipboard(report, "Report copied") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logBuffer.append("[$ts] ").append(msg).append('\n')
        binding.tvOutput.text = logBuffer.toString()
        binding.svOutput.post { binding.svOutput.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyToClipboard(text: String, msg: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("MariaRay", text))
        toast(msg)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
