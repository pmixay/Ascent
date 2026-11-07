package com.pmixay.ascent.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Deliberate, watch-local controls. Capture continues in the visible foreground service. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var elapsed: TextView
    private lateinit var gaps: TextView
    private lateinit var latest: TextView
    private lateinit var start: Button
    private lateinit var pause: Button
    private lateinit var stop: Button
    private var inlineError: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ink = Color.rgb(24, 25, 19)
        val yellow = Color.rgb(255, 204, 66)
        val pale = Color.rgb(255, 247, 218)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(19), dp(26), dp(19), dp(30))
            setBackgroundColor(ink)
        }
        root.addView(label("Ascent.", 25f, yellow, Typeface.BOLD), line())
        root.addView(label("Ваш разговор", 15f, pale, Typeface.NORMAL), line())
        status = label("Готово", 19f, pale, Typeface.BOLD).also { root.addView(it, line(dp(10))) }
        connection = label("Телефон не подключён", 16f, pale, Typeface.NORMAL)
        elapsed = label("00:00", 22f, yellow, Typeface.BOLD)
        gaps = label("Пропуски: 0", 16f, pale, Typeface.NORMAL)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.rgb(38, 39, 29), Color.rgb(106, 93, 55))
            addView(connection, line())
            addView(elapsed, line(dp(8)))
            addView(gaps, line(dp(8)))
        }
        root.addView(panel, line(dp(9)))
        start = button("Начать слушать", yellow, ink).also { button ->
            button.setOnClickListener { requestCapture() }
            root.addView(button, line(dp(14)))
        }
        pause = button("Пауза", Color.rgb(44, 45, 34), pale).also { button ->
            button.setOnClickListener {
                val command = if (WatchCaptureService.snapshot().phase == WatchCaptureService.Phase.PAUSED)
                    WatchCaptureService.ACTION_RESUME else WatchCaptureService.ACTION_PAUSE
                startService(Intent(this, WatchCaptureService::class.java).setAction(command))
            }
            root.addView(button, line(dp(9)))
        }
        stop = button("Остановить", Color.rgb(44, 45, 34), pale).also { button ->
            button.setOnClickListener {
                startService(Intent(this, WatchCaptureService::class.java).setAction(WatchCaptureService.ACTION_STOP))
            }
            root.addView(button, line(dp(9)))
        }
        root.addView(label("Последний ответ с телефона", 15f, yellow, Typeface.BOLD), line(dp(18)))
        latest = label("Пока нет расшифровки", 16f, pale, Typeface.NORMAL).also { root.addView(it, line(dp(7))) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun requestCapture() {
        val required = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        choosePhoneAndStart()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                choosePhoneAndStart()
            } else {
                inlineError = "Разрешите микрофон и Bluetooth для соединения с телефоном"
                render()
            }
        }
    }

    @SuppressLint("MissingPermission") // requestCapture asks for BLUETOOTH_CONNECT before this call.
    private fun choosePhoneAndStart() {
        try {
            val manager = getSystemService(BluetoothManager::class.java)
            val phones = manager.adapter?.bondedDevices.orEmpty()
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
                .sortedBy { it.name ?: it.address }
            when (phones.size) {
                0 -> { inlineError = "Сначала сопрягите часы с телефоном в настройках Bluetooth"; render() }
                1 -> begin(phones.first().address)
                else -> AlertDialog.Builder(this)
                    .setTitle("Выберите телефон")
                    .setItems(phones.map { it.name ?: it.address }.toTypedArray()) { _, index ->
                        begin(phones[index].address)
                    }.setNegativeButton("Отмена", null).show()
            }
        } catch (_: SecurityException) {
            inlineError = "Нет доступа к сопряжённому телефону"
            render()
        }
    }

    private fun begin(address: String) {
        inlineError = null
        startForegroundService(Intent(this, WatchCaptureService::class.java)
            .setAction(WatchCaptureService.ACTION_START)
            .putExtra(WatchCaptureService.EXTRA_PHONE_ADDRESS, address))
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun render() {
        if (!::status.isInitialized) return
        val state = WatchCaptureService.snapshot()
        status.text = inlineError ?: state.message
        connection.text = state.phoneName?.let { "Телефон: $it" } ?: "Телефон не подключён"
        val end = state.stoppedElapsedMs.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime()
        val seconds = if (state.startedElapsedMs > 0) ((end - state.startedElapsedMs) / 1000)
            .coerceAtLeast(0) else 0
        elapsed.text = String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        gaps.text = "Пропущено фрагментов: ${state.droppedFrames}"
        latest.text = state.latestResult.ifBlank { "Пока нет расшифровки" }
        val active = state.phase == WatchCaptureService.Phase.CONNECTING ||
            state.phase == WatchCaptureService.Phase.LISTENING || state.phase == WatchCaptureService.Phase.PAUSED
        start.visibility = if (active) View.GONE else View.VISIBLE
        pause.visibility = if (state.phase == WatchCaptureService.Phase.LISTENING ||
            state.phase == WatchCaptureService.Phase.PAUSED) View.VISIBLE else View.GONE
        pause.text = if (state.phase == WatchCaptureService.Phase.PAUSED) "Продолжить" else "Пауза"
        stop.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun label(value: String, size: Float, color: Int, weight: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, weight)
        gravity = Gravity.CENTER_HORIZONTAL
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun button(value: String, backgroundColor: Int, textColor: Int) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        minimumHeight = dp(52)
        backgroundTintList = ColorStateList.valueOf(backgroundColor)
        setTextColor(textColor)
        contentDescription = value
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(23).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun line(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private const val PERMISSION_REQUEST = 70
        private const val NOTIFICATION_REQUEST = 71
    }
}
