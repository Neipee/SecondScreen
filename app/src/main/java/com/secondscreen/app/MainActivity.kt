package com.secondscreen.app

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.secondscreen.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var displayManager: DisplayManager
    private lateinit var adapter: AppAdapter

    private val executor = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayRemoved(displayId: Int) {
            refreshDisplays()
        }

        override fun onDisplayChanged(displayId: Int) {
            refreshDisplays()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        adapter = AppAdapter { app -> launchOnExternal(app) }
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = adapter

        binding.refreshButton.setOnClickListener { refreshDisplays() }
        binding.searchInput.doAfterTextChanged { text ->
            adapter.filter(text?.toString() ?: "")
        }

        renderLog()
        refreshDisplays()
        loadApps()
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        refreshDisplays()
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun findExternalDisplay(): Display? {
        val presentation = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (presentation != null) return presentation
        return displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
    }

    private fun refreshDisplays() {
        val all = displayManager.displays
        val presentationIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        val external = findExternalDisplay()

        if (external != null) {
            val mode = external.mode
            binding.statusText.text = getString(R.string.status_found) + "\n" +
                getString(
                    R.string.status_details,
                    external.displayId,
                    external.name,
                    mode.physicalWidth,
                    mode.physicalHeight
                )
        } else {
            binding.statusText.text = getString(R.string.status_none)
        }

        val yes = getString(R.string.yes)
        val no = getString(R.string.no)
        binding.displaysText.text = all.joinToString("\n") { d ->
            val m = d.mode
            getString(
                R.string.display_line,
                d.displayId,
                d.name,
                m.physicalWidth,
                m.physicalHeight,
                d.flags,
                if (d.displayId in presentationIds) yes else no
            )
        }

        log(
            getString(
                R.string.log_displays_refreshed,
                all.size,
                external?.displayId?.toString() ?: "-"
            )
        )
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val result = try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .map { ri ->
                        val ai = ri.activityInfo
                        AppEntry(
                            label = ri.loadLabel(pm).toString(),
                            packageName = ai.packageName,
                            component = android.content.ComponentName(ai.packageName, ai.name),
                            icon = ri.loadIcon(pm)
                        )
                    }
                    .sortedBy { it.label.lowercase(Locale.getDefault()) }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.setApps(result)
                adapter.filter(binding.searchInput.text?.toString() ?: "")
                log(getString(R.string.log_apps_loaded, result.size))
            }
        }
    }

    private fun launchOnExternal(app: AppEntry) {
        val display = findExternalDisplay()
        if (display == null) {
            log(getString(R.string.log_no_display))
            showError(getString(R.string.err_no_display), null)
            return
        }
        val displayId = display.displayId
        log(getString(R.string.log_launching, app.label, displayId))

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
            log(getString(R.string.log_launch_sent, app.label, displayId))
        } catch (e: SecurityException) {
            handleError(e, R.string.err_security)
        } catch (e: ActivityNotFoundException) {
            handleError(e, R.string.err_not_found)
        } catch (e: IllegalArgumentException) {
            handleError(e, R.string.err_illegal_arg)
        } catch (e: Exception) {
            handleError(e, R.string.err_generic)
        }
    }

    private fun handleError(e: Exception, explanationRes: Int) {
        val name = e.javaClass.simpleName
        log(getString(R.string.log_launch_error, name, e.message ?: "-"))
        showError(getString(explanationRes), e)
    }

    private fun showError(explanation: String, e: Exception?) {
        val message = if (e != null) {
            explanation + "\n\n" + getString(
                R.string.error_details,
                e.javaClass.name,
                e.message ?: "-"
            )
        } else {
            explanation
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun log(message: String) {
        logLines.addLast(timeFormat.format(Date()) + "  " + message)
        while (logLines.size > 8) logLines.removeFirst()
        renderLog()
    }

    private fun renderLog() {
        binding.logText.text =
            if (logLines.isEmpty()) getString(R.string.log_empty) else logLines.joinToString("\n")
    }
}
