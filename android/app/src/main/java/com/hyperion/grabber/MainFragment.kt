package com.hyperion.grabber

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels

class MainFragment : Fragment() {

    private val vm: GrabberViewModel by activityViewModels()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastFrameCount = 0L
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateLiveStats()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)

        bindSettingRow(view.findViewById(R.id.rowHost),       R.string.setting_host,       SettingKey.HOST)
        bindSettingRow(view.findViewById(R.id.rowPort),       R.string.setting_port,       SettingKey.PORT)
        bindSettingRow(view.findViewById(R.id.rowFps),        R.string.setting_fps,        SettingKey.FPS)
        bindSettingRow(view.findViewById(R.id.rowResolution), R.string.setting_resolution, SettingKey.RESOLUTION)
        bindSettingRow(view.findViewById(R.id.rowBrightness), R.string.setting_brightness, SettingKey.BRIGHTNESS)

        val rowSchedule    = view.findViewById<View>(R.id.rowSchedule)
        rowSchedule.findViewById<TextView>(R.id.settingLabel).setText(R.string.setting_schedule)
        rowSchedule.setOnClickListener { showScheduleDialog() }
        rowSchedule.setOnFocusChangeListener { v, focused -> v.isSelected = focused }

        val statusDot      = view.findViewById<View>(R.id.statusDot)
        val statusText     = view.findViewById<TextView>(R.id.statusText)
        val connectionInfo = view.findViewById<TextView>(R.id.connectionInfo)
        val btnStartStop   = view.findViewById<Button>(R.id.btnStartStop)
        val btnTestLeds    = view.findViewById<Button>(R.id.btnTestLeds)
        val testLedResult  = view.findViewById<TextView>(R.id.testLedResult)
        val borderTop   = view.findViewById<View>(R.id.borderTop)
        val borderBottom= view.findViewById<View>(R.id.borderBottom)
        val borderLeft  = view.findViewById<View>(R.id.borderLeft)
        val borderRight = view.findViewById<View>(R.id.borderRight)
        val liveStats   = view.findViewById<TextView>(R.id.liveStats)

        fun refreshToggleButton() {
            val running   = vm.grabberStatus.value == GrabberViewModel.GrabberStatus.RUNNING
            val connected = vm.connectionState.value is ConnectionState.Connected
            val hostOk    = !vm.host.value.isNullOrBlank()
            btnStartStop.text      = getString(if (running) R.string.btn_stop else R.string.btn_start)
            btnStartStop.isEnabled = running || (connected && hostOk)
        }

        vm.host.observe(viewLifecycleOwner) {
            updateRow(view.findViewById(R.id.rowHost), it)
            refreshToggleButton()
        }
        vm.port.observe(viewLifecycleOwner)         { updateRow(view.findViewById(R.id.rowPort), protocolLabel(it)) }
        vm.fps.observe(viewLifecycleOwner)          { updateRow(view.findViewById(R.id.rowFps), "$it fps") }
        vm.targetWidth.observe(viewLifecycleOwner)  {
            updateRow(view.findViewById(R.id.rowResolution), "${it}×${vm.targetHeight.value}")
        }
        vm.targetHeight.observe(viewLifecycleOwner) {
            updateRow(view.findViewById(R.id.rowResolution), "${vm.targetWidth.value}×$it")
        }
        vm.brightness.observe(viewLifecycleOwner) {
            updateRow(view.findViewById(R.id.rowBrightness), "$it%")
        }

        fun refreshScheduleRow() {
            val mode  = vm.scheduleMode.value      ?: ScheduleMode.OFF
            val start = vm.scheduleStartHour.value ?: 19
            val end   = vm.scheduleEndHour.value   ?: 22
            val label = when (mode) {
                ScheduleMode.OFF    -> getString(R.string.schedule_off)
                ScheduleMode.FIXED  -> getString(R.string.schedule_fixed, start, end)
                ScheduleMode.SUNSET -> getString(R.string.schedule_sunset, end)
            }
            updateRow(rowSchedule, label)
        }

        vm.scheduleMode.observe(viewLifecycleOwner)      { refreshScheduleRow() }
        vm.scheduleStartHour.observe(viewLifecycleOwner) { refreshScheduleRow() }
        vm.scheduleEndHour.observe(viewLifecycleOwner)   { refreshScheduleRow() }

        vm.grabberStatus.observe(viewLifecycleOwner) { status ->
            val running = status == GrabberViewModel.GrabberStatus.RUNNING
            refreshToggleButton()
            val borderVisible = if (running) View.VISIBLE else View.GONE
            borderTop.visibility    = borderVisible
            borderBottom.visibility = borderVisible
            borderLeft.visibility   = borderVisible
            borderRight.visibility  = borderVisible
            liveStats.visibility = borderVisible
            if (running) {
                lastFrameCount = 0L
                uiHandler.post(statsRunnable)
            } else {
                uiHandler.removeCallbacks(statsRunnable)
                liveStats.text = ""
            }
        }

        vm.connectionState.observe(viewLifecycleOwner) { connState ->
            val (dotColor, statusLabel, infoText) = when (connState) {
                is ConnectionState.Idle      ->
                    Triple(R.color.status_idle,    R.string.status_idle,     "")
                is ConnectionState.Checking  ->
                    Triple(R.color.status_idle,    R.string.status_checking, getString(R.string.conn_checking))
                is ConnectionState.Connected ->
                    Triple(R.color.status_running, R.string.status_connected,
                        getString(R.string.conn_connected, connState.ledCount, connState.width, connState.height))
                is ConnectionState.Failed    ->
                    Triple(R.color.status_error,   R.string.status_error,    getString(R.string.conn_failed, connState.reason))
            }
            statusDot.backgroundTintList = ContextCompat.getColorStateList(requireContext(), dotColor)
            statusText.setText(statusLabel)
            connectionInfo.text = infoText
            refreshToggleButton()
        }

        vm.testLedState.observe(viewLifecycleOwner) { msg ->
            testLedResult.text = msg ?: ""
            btnTestLeds.isEnabled = msg == null || !msg.startsWith("Sending")
        }

        btnStartStop.setOnClickListener {
            if (vm.grabberStatus.value == GrabberViewModel.GrabberStatus.RUNNING)
                vm.stopGrabber(requireContext())
            else
                (activity as? MainActivity)?.requestProjection()
        }
        btnTestLeds.setOnClickListener { vm.testLeds() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        uiHandler.removeCallbacks(statsRunnable)
    }

    private fun updateLiveStats() {
        val rootView = view ?: return
        val liveStats = rootView.findViewById<TextView>(R.id.liveStats) ?: return

        val current = ScreenGrabberService.frameCount
        val fps = (current - lastFrameCount).toInt()
        lastFrameCount = current
        liveStats.text = "↑ ${fps} fps"
    }

    private fun bindSettingRow(row: View, labelRes: Int, key: SettingKey) {
        row.findViewById<TextView>(R.id.settingLabel).setText(labelRes)
        row.setOnClickListener { showEditDialog(key) }
        row.setOnFocusChangeListener { v, focused -> v.isSelected = focused }
    }

    private fun updateRow(row: View, value: String) {
        row.findViewById<TextView>(R.id.settingValue).text = value
    }

    private fun showEditDialog(key: SettingKey) {
        if (key == SettingKey.PORT)        { showProtocolPicker();   return }
        if (key == SettingKey.BRIGHTNESS)  { showBrightnessSlider(); return }

        val (title, hint, inputType, current) = when (key) {
            SettingKey.HOST -> EditMeta(
                getString(R.string.setting_host), "e.g. 192.168.1.100",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                vm.host.value ?: ""
            )
            SettingKey.PORT -> EditMeta("", "", 0, "") // unreachable — handled above
            SettingKey.FPS -> EditMeta(
                getString(R.string.setting_fps), "1–60",
                InputType.TYPE_CLASS_NUMBER,
                vm.fps.value?.toString() ?: "25"
            )
            SettingKey.RESOLUTION -> EditMeta(
                getString(R.string.setting_resolution), "Width in pixels (e.g. 64)",
                InputType.TYPE_CLASS_NUMBER,
                vm.targetWidth.value?.toString() ?: "64"
            )
            SettingKey.BRIGHTNESS -> EditMeta("", "", 0, "") // unreachable — handled above
        }

        val editText = EditText(requireContext()).apply {
            this.inputType = inputType
            this.hint      = hint
            setText(current)
            selectAll()
            setPadding(48, 32, 48, 32)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(editText)
            .setPositiveButton(getString(R.string.edit_confirm)) { _, _ ->
                applyValue(key, editText.text.toString().trim())
            }
            .setNegativeButton(getString(R.string.edit_cancel), null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        val confirmBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                confirmBtn.isEnabled = s?.isNotBlank() == true
            }
        })
    }

    private val protocols = listOf(
        "Flatbuffer"  to 19400,
        "Proto"       to 19445,
        "JSON (read)" to 19444,
    )

    private fun protocolLabel(port: Int): String {
        val name = protocols.firstOrNull { it.second == port }?.first
        return if (name != null) "$name · $port" else "Custom · $port"
    }

    private fun showBrightnessSlider() {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val valueLabel = TextView(ctx).apply {
            textSize  = 32f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }

        val seekBar = SeekBar(ctx).apply {
            max      = 100
            progress = vm.brightness.value ?: 100
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), 0)
        }

        valueLabel.text = "${seekBar.progress}%"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueLabel.text = "$progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar)  = Unit
        })

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(valueLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(seekBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (8 * dp).toInt() })
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_brightness)
            .setView(layout)
            .setPositiveButton(R.string.edit_confirm) { _, _ ->
                vm.saveBrightness(seekBar.progress)
            }
            .setNegativeButton(R.string.edit_cancel, null)
            .show()
    }

    private fun showProtocolPicker() {
        val currentPort = vm.port.value ?: 19400
        val labels = protocols.map { (name, port) -> "$name  ($port)" }.toTypedArray()
        val checked = protocols.indexOfFirst { it.second == currentPort }.coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.setting_port))
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                vm.savePort(protocols[which].second)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.edit_cancel, null)
            .show()
    }

    private fun showScheduleDialog() {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((48 * dp).toInt(), (24 * dp).toInt(), (48 * dp).toInt(), (8 * dp).toInt())
        }

        val radioGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        val rbOff      = RadioButton(ctx).apply { text = getString(R.string.schedule_mode_off);    id = View.generateViewId() }
        val rbFixed    = RadioButton(ctx).apply { text = getString(R.string.schedule_mode_fixed);  id = View.generateViewId() }
        val rbSunset   = RadioButton(ctx).apply { text = getString(R.string.schedule_mode_sunset); id = View.generateViewId() }
        radioGroup.addView(rbOff)
        radioGroup.addView(rbFixed)
        radioGroup.addView(rbSunset)
        layout.addView(radioGroup)

        val startLabel = TextView(ctx).apply {
            text    = getString(R.string.schedule_start_label)
            setPadding(0, (16 * dp).toInt(), 0, 4)
        }
        val startEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint      = "0–23"
            setText(vm.scheduleStartHour.value?.toString() ?: "19")
        }
        val endLabel = TextView(ctx).apply {
            text    = getString(R.string.schedule_end_label)
            setPadding(0, (12 * dp).toInt(), 0, 4)
        }
        val endEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint      = "0–23"
            setText(vm.scheduleEndHour.value?.toString() ?: "22")
        }

        layout.addView(startLabel)
        layout.addView(startEdit)
        layout.addView(endLabel)
        layout.addView(endEdit)

        // Pre-select current mode
        when (vm.scheduleMode.value ?: ScheduleMode.OFF) {
            ScheduleMode.OFF    -> radioGroup.check(rbOff.id)
            ScheduleMode.FIXED  -> radioGroup.check(rbFixed.id)
            ScheduleMode.SUNSET -> radioGroup.check(rbSunset.id)
        }

        fun updateStartVisibility() {
            val showStart = radioGroup.checkedRadioButtonId == rbFixed.id
            startLabel.visibility = if (showStart) View.VISIBLE else View.GONE
            startEdit.visibility  = if (showStart) View.VISIBLE else View.GONE
        }
        updateStartVisibility()
        radioGroup.setOnCheckedChangeListener { _, _ -> updateStartVisibility() }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_schedule)
            .setView(layout)
            .setPositiveButton(R.string.edit_confirm) { _, _ ->
                val mode = when (radioGroup.checkedRadioButtonId) {
                    rbFixed.id  -> ScheduleMode.FIXED
                    rbSunset.id -> ScheduleMode.SUNSET
                    else        -> ScheduleMode.OFF
                }
                val start = startEdit.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 19
                val end   = endEdit.text.toString().toIntOrNull()?.coerceIn(0, 23)   ?: 22
                vm.saveSchedule(requireContext(), mode, start, end)
            }
            .setNegativeButton(R.string.edit_cancel, null)
            .show()
    }

    private fun applyValue(key: SettingKey, raw: String) {
        when (key) {
            SettingKey.HOST       -> if (raw.isNotEmpty()) vm.saveHost(raw)
            SettingKey.PORT       -> raw.toIntOrNull()?.coerceIn(1, 65535)?.let { vm.savePort(it) }
            SettingKey.FPS        -> raw.toIntOrNull()?.coerceIn(1, 60)?.let { vm.saveFps(it) }
            SettingKey.RESOLUTION -> raw.toIntOrNull()?.coerceIn(8, 256)?.let { w ->
                vm.saveResolution(w, (w * 9 / 16).coerceAtLeast(8))
            }
            SettingKey.BRIGHTNESS -> raw.toIntOrNull()?.let { vm.saveBrightness(it) }
        }
    }

    private data class EditMeta(
        val title: String,
        val hint: String,
        val inputType: Int,
        val current: String
    )
}
