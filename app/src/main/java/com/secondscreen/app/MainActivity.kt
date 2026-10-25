package com.secondscreen.app

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
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
import com.secondscreen.app.privileged.AudioRouting
import com.secondscreen.app.privileged.ExternalDisplayTweaks
import com.secondscreen.app.privileged.PrivResult
import com.secondscreen.app.privileged.ShizukuShell
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

    private var permToken: Any? = null
    private var sliderDisplayId = -2
    private var currentDensity: Int? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            refreshDisplays()
            autoReapplyOnAdded()
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
        supportActionBar?.subtitle = "Version " + BuildConfig.VERSION_NAME

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
        setupShizukuCard()
        setupDensityCard()
        setupAudioCard()

        renderLog()
        refreshDisplays()
        renderCursorStatus()
        renderDuo()
        loadApps()
        renderShizuku()
    }

    override fun onResume() {
        super.onResume()
        renderCursorStatus()
        renderShizuku()
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
        permToken?.let {
            try {
                ShizukuShell.removePermissionResultListener(it)
            } catch (e: Exception) {
            }
        }
        permToken = null
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
        renderShizuku()

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
        binding.openAppInfoButton.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                handleError(e, R.string.err_generic)
            }
        }
        binding.autoEnableButton.isEnabled = false
        binding.autoEnableButton.setOnClickListener { autoEnableService() }
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

    private fun autoEnableService() {
        val component = ComponentName(this, CursorAccessibilityService::class.java).flattenToString()
        val pkg = packageName
        binding.autoEnableButton.isEnabled = false
        runPriv({
            val r1 = ShizukuShell.exec("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
            runOnUiThread { log(getString(R.string.log_auto_step, "appops", r1.message.ifEmpty { "ok" })) }
            val r2 = ShizukuShell.exec("settings get secure enabled_accessibility_services")
            runOnUiThread { log(getString(R.string.log_auto_step, "get", r2.message.ifEmpty { "-" })) }
            if (!r2.ok) {
                r2
            } else {
                val list = r2.message.trim().split(':')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "null" }
                    .toMutableList()
                if (!list.contains(component)) list.add(component)
                val value = list.joinToString(":")
                val r3 = ShizukuShell.exec("settings put secure enabled_accessibility_services '$value'")
                runOnUiThread { log(getString(R.string.log_auto_step, "put services", r3.message.ifEmpty { "ok" })) }
                if (!r3.ok) {
                    r3
                } else {
                    val r4 = ShizukuShell.exec("settings put secure accessibility_enabled 1")
                    runOnUiThread { log(getString(R.string.log_auto_step, "put enabled", r4.message.ifEmpty { "ok" })) }
                    r4
                }
            }
        }) { r ->
            binding.autoEnableButton.isEnabled = shizukuReady()
            handler.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                renderCursorStatus()
                if (r.ok) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.auto_enable_title_ok)
                        .setMessage(
                            getString(R.string.auto_enable_ok) + "\n\n" +
                                getString(
                                    if (CursorAccessibilityService.instance != null) R.string.cursor_active
                                    else R.string.cursor_inactive
                                )
                        )
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    showPrivError(R.string.auto_enable_title_error, r.message)
                }
            }, 1500L)
        }
    }

    private fun showShizukuGuide(prefix: String = "") {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shizuku_guide_title)
            .setMessage(prefix + getString(R.string.shizuku_guide_text))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ---- Shizuku / density / audio ----

    private fun shizukuReady(): Boolean = try {
        ShizukuShell.isInstalled(this) && ShizukuShell.isRunning() && ShizukuShell.hasPermission()
    } catch (e: Exception) {
        false
    }

    private fun runPriv(work: () -> PrivResult, done: (PrivResult) -> Unit) {
        try {
            executor.execute {
                val r = try {
                    work()
                } catch (e: Exception) {
                    PrivResult(false, e.javaClass.simpleName + ": " + (e.message ?: "-"))
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    done(r)
                }
            }
        } catch (e: Exception) {
            done(PrivResult(false, e.message ?: "-"))
        }
    }

    private fun showPrivError(titleRes: Int, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun setupShizukuCard() {
        binding.shizukuAuthorizeButton.setOnClickListener {
            val running = try { ShizukuShell.isRunning() } catch (e: Exception) { false }
            if (!running) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.shizuku_not_running_title)
                    .setMessage(
                        getString(R.string.shizuku_not_running_msg) +
                            getString(R.string.shizuku_guide_text)
                    )
                    .setPositiveButton(R.string.close, null)
                    .show()
            } else {
                try {
                    ShizukuShell.requestPermission()
                } catch (e: Exception) {
                    handleError(e, R.string.err_generic)
                }
            }
        }
        binding.shizukuGuideButton.setOnClickListener { showShizukuGuide() }
        binding.shizukuOpenButton.setOnClickListener {
            try {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i == null) {
                    showError(getString(R.string.shizuku_not_installed), null)
                } else {
                    startActivity(i)
                }
            } catch (e: Exception) {
                handleError(e, R.string.err_generic)
            }
        }
        binding.devOptionsButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                handleError(e, R.string.err_not_found)
            } catch (e: Exception) {
                handleError(e, R.string.err_generic)
            }
        }
        binding.shizukuInstallButton.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                    )
                } catch (e2: Exception) {
                    handleError(e2, R.string.err_generic)
                }
            }
        }
        try {
            permToken = ShizukuShell.addPermissionResultListener { granted ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    log(
                        getString(
                            R.string.shizuku_perm_result,
                            getString(if (granted) R.string.granted else R.string.denied)
                        )
                    )
                    renderShizuku()
                }
            }
        } catch (e: Exception) {
            log(getString(R.string.log_launch_error, e.javaClass.simpleName, e.message ?: "-"))
        }
    }

    private fun renderShizuku() {
        val installed = try { ShizukuShell.isInstalled(this) } catch (e: Exception) { false }
        val running = try { ShizukuShell.isRunning() } catch (e: Exception) { false }
        val perm = try { ShizukuShell.hasPermission() } catch (e: Exception) { false }
        binding.shizukuStatus.setText(
            when {
                !installed && !running -> R.string.shizuku_not_installed
                !running -> R.string.shizuku_not_running
                !perm -> R.string.shizuku_no_permission
                else -> R.string.shizuku_ready
            }
        )
        binding.shizukuAuthorizeButton.isEnabled = running && !perm
        binding.shizukuInstallButton.isEnabled = !installed
        binding.shizukuOpenButton.visibility = if (installed) View.VISIBLE else View.GONE
        binding.autoEnableButton.isEnabled = perm
        renderDensity()
        renderAudio()
    }

    private fun snapDpi(v: Int): Int {
        val r = ((v + 10) / 20) * 20
        return r.coerceIn(100, 480)
    }

    private fun updateDensityValueLabel() {
        binding.densityValue.text =
            getString(R.string.density_value, binding.densitySlider.value.toInt())
    }

    private fun setupDensityCard() {
        binding.densitySlider.value = 240f
        updateDensityValueLabel()
        binding.densitySlider.addOnChangeListener { _, _, _ -> updateDensityValueLabel() }
        binding.densityPresetSmall.setOnClickListener { binding.densitySlider.value = 160f }
        binding.densityPresetMedium.setOnClickListener { binding.densitySlider.value = 240f }
        binding.densityPresetLarge.setOnClickListener { binding.densitySlider.value = 320f }
        binding.densityAutoSwitch.isChecked = DisplayPrefs.isAutoReapplyDensity(this)
        binding.densityAutoSwitch.setOnCheckedChangeListener { _, checked ->
            DisplayPrefs.setAutoReapplyDensity(this, checked)
        }
        binding.densityApplyButton.setOnClickListener {
            val display = DisplayPrefs.resolveTargetDisplay(this) ?: return@setOnClickListener
            val id = display.displayId
            val dpi = snapDpi(binding.densitySlider.value.toInt())
            runPriv({ ExternalDisplayTweaks.setDensity(id, dpi) }) { r ->
                log(getString(R.string.log_density_apply, id, dpi, r.message))
                if (r.ok) {
                    DisplayPrefs.setSavedDensity(this, id, dpi)
                } else {
                    showPrivError(R.string.density_title_error, r.message)
                }
                refreshCurrentDensity()
            }
        }
        binding.densityResetButton.setOnClickListener {
            val display = DisplayPrefs.resolveTargetDisplay(this) ?: return@setOnClickListener
            val id = display.displayId
            runPriv({ ExternalDisplayTweaks.resetDensity(id) }) { r ->
                log(getString(R.string.log_density_reset, id, r.message))
                if (r.ok) {
                    DisplayPrefs.clearSavedDensity(this, id)
                } else {
                    showPrivError(R.string.density_title_error, r.message)
                }
                refreshCurrentDensity()
            }
        }
        renderDensity()
    }

    private fun renderDensity() {
        val display = DisplayPrefs.resolveTargetDisplay(this)
        val ready = shizukuReady()
        val enabled = display != null && ready
        binding.densitySlider.isEnabled = enabled
        binding.densityPresetSmall.isEnabled = enabled
        binding.densityPresetMedium.isEnabled = enabled
        binding.densityPresetLarge.isEnabled = enabled
        binding.densityApplyButton.isEnabled = enabled
        binding.densityResetButton.isEnabled = enabled
        binding.densityAutoSwitch.isEnabled = ready
        val note = when {
            display == null -> getString(R.string.density_no_display)
            !ready -> getString(R.string.shizuku_need)
            else -> ""
        }
        binding.densityNote.text = note
        binding.densityNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        if (display != null && display.displayId != sliderDisplayId) {
            sliderDisplayId = display.displayId
            val saved = DisplayPrefs.getSavedDensity(this, display.displayId)
            binding.densitySlider.value = snapDpi(saved ?: 240).toFloat()
            updateDensityValueLabel()
        }
        if (display == null) {
            currentDensity = null
            binding.densityCurrent.text =
                getString(R.string.density_current, getString(R.string.density_unknown))
        } else if (ready) {
            refreshCurrentDensity()
        } else {
            binding.densityCurrent.text =
                getString(R.string.density_current, getString(R.string.density_unknown))
        }
    }

    private fun refreshCurrentDensity() {
        val display = DisplayPrefs.resolveTargetDisplay(this) ?: return
        val id = display.displayId
        if (!shizukuReady()) return
        try {
            executor.execute {
                val d = try {
                    ExternalDisplayTweaks.getDensity(id)
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (DisplayPrefs.resolveTargetDisplay(this)?.displayId != id) return@runOnUiThread
                    currentDensity = d
                    binding.densityCurrent.text = getString(
                        R.string.density_current,
                        if (d != null) getString(R.string.density_value, d)
                        else getString(R.string.density_unknown)
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun setupAudioCard() {
        binding.speakerSwitch.isChecked = DisplayPrefs.getForceSpeaker(this)
        binding.speakerSwitch.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            runPriv({ AudioRouting.forcePhoneSpeaker(this, checked) }) { r ->
                log(
                    getString(
                        R.string.log_audio_speaker,
                        getString(if (checked) R.string.on_word else R.string.off_word),
                        r.message
                    )
                )
                if (r.ok) {
                    DisplayPrefs.setForceSpeaker(this, checked)
                } else {
                    binding.speakerSwitch.isChecked = !checked
                    showPrivError(R.string.audio_error_title, r.message)
                }
            }
        }
        binding.audioSwitcherButton.setOnClickListener {
            try {
                val r = AudioRouting.openSystemOutputSwitcher(this)
                log(getString(R.string.log_audio_switcher, r.message))
                if (!r.ok) showPrivError(R.string.audio_error_title, r.message)
            } catch (e: Exception) {
                handleError(e, R.string.err_generic)
            }
        }
        renderAudio()
    }

    private fun renderAudio() {
        val ready = shizukuReady()
        binding.speakerSwitch.isEnabled = ready
        binding.audioNote.text = getString(R.string.shizuku_need)
        binding.audioNote.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun autoReapplyOnAdded() {
        if (!shizukuReady()) return
        val display = DisplayPrefs.resolveTargetDisplay(this)
        if (display != null && DisplayPrefs.isAutoReapplyDensity(this)) {
            val id = display.displayId
            val dpi = DisplayPrefs.getSavedDensity(this, id)
            if (dpi != null) {
                runPriv({ ExternalDisplayTweaks.setDensity(id, dpi) }) { r ->
                    log(getString(R.string.log_density_auto, id, dpi, r.message))
                    refreshCurrentDensity()
                }
            }
        }
        if (DisplayPrefs.getForceSpeaker(this)) {
            runPriv({ AudioRouting.forcePhoneSpeaker(this, true) }) { r ->
                log(getString(R.string.log_audio_auto, r.message))
            }
        }
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
