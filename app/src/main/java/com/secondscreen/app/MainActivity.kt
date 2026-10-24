package com.secondscreen.app

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.secondscreen.app.cursor.CursorAccessibilityService
import com.secondscreen.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var displayManager: DisplayManager
    private lateinit var adapter: AppAdapter

    private var apps: List<AppEntry> = emptyList()
    private var spinnerIds: List<Int> = listOf(DisplayPrefs.AUTO)
    private var spinnerTouched = false
    private val handler = Handler(Looper.getMainLooper())

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

        adapter = AppAdapter { app -> showLaunchChoice(app) }
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = adapter

        binding.refreshButton.setOnClickListener { refreshDisplays() }
        binding.searchInput.doAfterTextChanged { text ->
            adapter.filter(text?.toString() ?: "")
        }

        setupOutputSpinner()
        setupCursorCard()
        setupDuoCard()

        renderLog()
        refreshDisplays()
        renderCursorStatus()
        renderDuo()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        renderCursorStatus()
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
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshDisplays() {
        val all = displayManager.displays
        val presentationIds = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()
        val external = DisplayPrefs.resolveTargetDisplay(this)

        if (external != null) {
            val mode = external.mode
            binding.statusText.text = getString(R.string.status_found) + "\n" +
                getString(R.string.status_target, external.displayId, external.name) + "\n" +
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

        renderOutputSpinner()

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
                apps = result
                renderDuo()
                adapter.setApps(result)
                adapter.filter(binding.searchInput.text?.toString() ?: "")
                log(getString(R.string.log_apps_loaded, result.size))
            }
        }
    }

    private fun showLaunchChoice(app: AppEntry) {
        val items = arrayOf(
            getString(R.string.action_open_external),
            getString(R.string.action_open_phone),
            getString(R.string.action_duo_phone),
            getString(R.string.action_duo_external)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchOnExternal(app)
                    1 -> launchOn(app, Display.DEFAULT_DISPLAY)
                    2 -> {
                        saveDuo(KEY_DUO_PHONE, app.component)
                        renderDuo()
                    }
                    3 -> {
                        saveDuo(KEY_DUO_EXT, app.component)
                        renderDuo()
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun launchOnExternal(app: AppEntry): Boolean {
        val display = DisplayPrefs.resolveTargetDisplay(this)
        if (display == null) {
            log(getString(R.string.log_no_display))
            showError(getString(R.string.err_no_display), null)
            return false
        }
        return launchOn(app, display.displayId)
    }

    private fun launchOn(app: AppEntry, displayId: Int): Boolean {
        log(getString(R.string.log_launching, app.label, displayId))
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = app.component
                addFlags(
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    } else {
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    }
                )
            }
            val options = ActivityOptions.makeBasic()
            options.setLaunchDisplayId(displayId)
            startActivity(intent, options.toBundle())
            log(getString(R.string.log_launch_sent, app.label, displayId))
            return true
        } catch (e: SecurityException) {
            handleError(e, R.string.err_security)
        } catch (e: ActivityNotFoundException) {
            handleError(e, R.string.err_not_found)
        } catch (e: IllegalArgumentException) {
            handleError(e, R.string.err_illegal_arg)
        } catch (e: Exception) {
            handleError(e, R.string.err_generic)
        }
        return false
    }

    // ---- Output display spinner ----

    private fun setupOutputSpinner() {
        binding.outputSpinner.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) spinnerTouched = true
            false
        }
        binding.outputSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (!spinnerTouched) return
                spinnerTouched = false
                val chosen = spinnerIds.getOrNull(position) ?: return
                if (chosen == DisplayPrefs.getPreferredDisplayId(this@MainActivity)) return
                DisplayPrefs.setPreferredDisplayId(this@MainActivity, chosen)
                CursorAccessibilityService.instance?.refreshFromPrefs()
                refreshDisplays()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun renderOutputSpinner() {
        val externals = DisplayPrefs.externalDisplays(this)
        val ids = mutableListOf(DisplayPrefs.AUTO)
        val labels = mutableListOf(getString(R.string.output_auto))
        for (d in externals) {
            val m = d.mode
            ids.add(d.displayId)
            labels.add(
                getString(R.string.output_item, d.displayId, d.name, m.physicalWidth, m.physicalHeight)
            )
        }
        val saved = DisplayPrefs.getPreferredDisplayId(this)
        var selected = ids.indexOf(saved)
        var note = ""
        if (saved != DisplayPrefs.AUTO && selected < 0) {
            note = getString(R.string.output_not_connected, saved)
        } else if (externals.isEmpty()) {
            note = getString(R.string.output_none)
        }
        if (selected < 0) selected = 0
        spinnerIds = ids
        val spinnerAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.outputSpinner.adapter = spinnerAdapter
        binding.outputSpinner.setSelection(selected, false)
        binding.outputNote.text = note
        binding.outputNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- Cursor card ----

    private fun setupCursorCard() {
        binding.enableServiceButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                handleError(e, R.string.err_generic)
            }
        }
        binding.openTouchpadButton.setOnClickListener {
            val service = CursorAccessibilityService.instance
            if (service == null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.cursor_need_service_title)
                    .setMessage(R.string.cursor_need_service_msg)
                    .setPositiveButton(R.string.close, null)
                    .show()
            } else {
                service.openTouchpad()
            }
        }
        binding.bubbleSwitch.isChecked = DisplayPrefs.isBubbleEnabled(this)
        binding.bubbleSwitch.setOnCheckedChangeListener { _, checked ->
            DisplayPrefs.setBubbleEnabled(this, checked)
            CursorAccessibilityService.instance?.refreshFromPrefs()
        }
        if (Build.VERSION.SDK_INT < 34) {
            binding.mouseSwitch.isChecked = false
            binding.mouseSwitch.isEnabled = false
            binding.mouseNote.visibility = View.VISIBLE
        } else {
            binding.mouseSwitch.isChecked = DisplayPrefs.isMouseModeEnabled(this)
            binding.mouseSwitch.setOnCheckedChangeListener { _, checked ->
                DisplayPrefs.setMouseModeEnabled(this, checked)
                CursorAccessibilityService.instance?.refreshFromPrefs()
            }
        }
    }

    private fun renderCursorStatus() {
        binding.cursorStatus.text = getString(
            if (CursorAccessibilityService.instance != null) R.string.cursor_active
            else R.string.cursor_inactive
        )
    }

    // ---- Duo mode ----

    private fun duoPrefs() = getSharedPreferences(DUO_PREFS, Context.MODE_PRIVATE)

    private fun saveDuo(key: String, component: ComponentName?) {
        duoPrefs().edit().apply {
            if (component == null) remove(key) else putString(key, component.flattenToString())
        }.apply()
    }

    private fun loadDuo(key: String): AppEntry? {
        val flat = duoPrefs().getString(key, null) ?: return null
        val component = ComponentName.unflattenFromString(flat) ?: return null
        return apps.firstOrNull { it.component == component }
    }

    private fun setupDuoCard() {
        binding.duoPhoneClear.setOnClickListener {
            saveDuo(KEY_DUO_PHONE, null)
            renderDuo()
        }
        binding.duoExtClear.setOnClickListener {
            saveDuo(KEY_DUO_EXT, null)
            renderDuo()
        }
        binding.launchBothButton.setOnClickListener { launchBoth() }
    }

    private fun renderDuo() {
        val phone = loadDuo(KEY_DUO_PHONE)
        val ext = loadDuo(KEY_DUO_EXT)
        binding.duoPhoneLabel.text = phone?.label ?: getString(R.string.duo_none)
        binding.duoPhoneIcon.setImageDrawable(phone?.icon)
        binding.duoExtLabel.text = ext?.label ?: getString(R.string.duo_none)
        binding.duoExtIcon.setImageDrawable(ext?.icon)
    }

    private fun launchBoth() {
        val phone = loadDuo(KEY_DUO_PHONE)
        val ext = loadDuo(KEY_DUO_EXT)
        if (phone == null && ext == null) {
            showError(getString(R.string.duo_nothing), null)
            return
        }
        log(getString(R.string.log_duo))
        var ok = true
        if (ext != null) ok = launchOnExternal(ext)
        if (phone != null && ok) {
            handler.postDelayed({
                if (!isFinishing && !isDestroyed) launchOn(phone, Display.DEFAULT_DISPLAY)
            }, 600L)
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

    private companion object {
        const val DUO_PREFS = "secondscreen_duo"
        const val KEY_DUO_PHONE = "duo_phone_component"
        const val KEY_DUO_EXT = "duo_external_component"
    }
}
