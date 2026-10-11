package com.numberai.gamebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

/** Accessibility helper with optional on-device OCR. Rare detection is best-effort, not a guarantee. */
class NumberAIService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val ocrExecutor = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val prefs by lazy { getSharedPreferences("number_ai", MODE_PRIVATE) }
    private var overlay: View? = null
    @Volatile private var running = false
    private var taps = 0
    private var rareHits = 0
    private var lastResultSignature = ""
    private var activePackage: String? = null
    private var waitingForGesture = false
    private var screenshotBusy = false
    private var lastOcrAt = 0L
    private var lastOcrText = ""
    private var lastOcrRare = ""
    private var lastOcrPlate = false
    private val gamePackage = "com.AksiCo.NumGen"
    private lateinit var status: TextView
    private lateinit var xInput: EditText
    private lateinit var yInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var maxInput: EditText
    private lateinit var pauseRare: CheckBox
    private lateinit var repeatEnabled: CheckBox
    private lateinit var ocrEnabled: CheckBox

    private val gestureTimeout = Runnable {
        if (waitingForGesture && running) stopAutomation("Тайм-аут жеста — остановлено")
    }
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            if (!repeatEnabled.isChecked) { stopAutomation("Автогенерация выключена"); return }
            if (activePackage != gamePackage) { stopAutomation("Игра не на переднем плане — остановлено"); return }
            if (waitingForGesture) { stopAutomation("Предыдущее нажатие ещё не завершено"); return }
            val max = maxInput.text.toString().toIntOrNull()?.coerceIn(1, 1_000_000) ?: 100
            if (taps >= max) { stopAutomation("Лимит $max нажатий достигнут"); return }
            // When visual recognition is enabled, do not tap again until a fresh screen check has completed.
            if (ocrEnabled.isChecked && Build.VERSION.SDK_INT >= 30) {
                if (screenshotBusy) { handler.postDelayed(this, 250L); return }
                requestScreenshot(autoContinue = true)
                return
            }
            doTap(max)
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); showOverlay() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.let { pkg ->
            if (pkg != packageName) {
                activePackage = pkg
                if (running && pkg != gamePackage) stopAutomation("Открыто другое приложение — остановлено")
            }
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != gamePackage) return
        val text = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { text.append(it).append(' ') }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { text.append(it).append(' ') }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        analyzeText(text.toString(), "ACCESSIBILITY")
    }

    private fun analyzeText(raw: String, source: String) {
        val all = raw.lowercase().replace('\n', ' ')
        val hasPlate = Regex("[а-яa-z]\u005cs*\\d{3}\\u005cs*[а-яa-z]{2,3}", RegexOption.IGNORE_CASE).containsMatchIn(all) ||
            all.contains("номер:") || all.contains("госномер")
        val rarity = when {
            all.contains("легендар") -> "ЛЕГЕНДАРНЫЙ"
            all.contains("эпичес") -> "ЭПИЧЕСКИЙ"
            all.contains("мифичес") -> "МИФИЧЕСКИЙ"
            all.contains("редкий") -> "РЕДКИЙ"
            else -> ""
        }
        if (hasPlate && rarity.isNotEmpty()) {
            val signature = "$rarity|${all.take(250)}"
            if (signature != lastResultSignature) {
                lastResultSignature = signature
                rareHits++
                handler.post {
                    if (::status.isInitialized) status.text = "ОБНАРУЖЕНО: $rarity ($source) • $rareHits"
                    if (::pauseRare.isInitialized && pauseRare.isChecked && running) stopAutomation("Пауза: обнаружен $rarity")
                }
            }
        }
    }

    private fun requestScreenshot(autoContinue: Boolean = false) {
        if (Build.VERSION.SDK_INT < 30 || screenshotBusy || activePackage != gamePackage) return
        screenshotBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (copy == null) { screenshotBusy = false; return }
                        val image = InputImage.fromBitmap(copy, 0)
                        recognizer.process(image)
                            .addOnSuccessListener(ocrExecutor) { visionText ->
                                val text = visionText.text
                                lastOcrText = text
                                lastOcrAt = System.currentTimeMillis()
                                val low = text.lowercase()
                                lastOcrPlate = Regex("[а-яa-z]\\s*\\d{3}\\s*[а-яa-z]{2,3}", RegexOption.IGNORE_CASE).containsMatchIn(low) || low.contains("госномер")
                                lastOcrRare = when {
                                    low.contains("легендар") -> "ЛЕГЕНДАРНЫЙ"
                                    low.contains("эпичес") -> "ЭПИЧЕСКИЙ"
                                    low.contains("мифичес") -> "МИФИЧЕСКИЙ"
                                    low.contains("редкий") -> "РЕДКИЙ"
                                    else -> ""
                                }
                                analyzeText(text, "OCR")
                                handler.post {
                                    screenshotBusy = false
                                    if (running && pauseRare.isChecked && lastOcrPlate && lastOcrRare.isNotEmpty()) {
                                        stopAutomation("OCR обнаружил $lastOcrRare — остановлено")
                                    } else if (autoContinue && running) {
                                        val max = maxInput.text.toString().toIntOrNull()?.coerceIn(1, 1_000_000) ?: 100
                                        doTap(max)
                                    } else if (::status.isInitialized) {
                                        status.text = if (text.isBlank()) "OCR: текст не распознан" else "OCR: экран прочитан • ${text.take(70).replace('\n',' ')}"
                                    }
                                }
                                copy.recycle()
                            }
                            .addOnFailureListener(ocrExecutor) {
                                copy.recycle()
                                handler.post {
                                    screenshotBusy = false
                                    lastOcrAt = System.currentTimeMillis()
                                    if (::status.isInitialized) status.text = "OCR не сработал — безопасная пауза"
                                    if (running) stopAutomation("Ошибка OCR: остановлено для безопасности")
                                }
                            }
                    } catch (_: Exception) {
                        try { result.hardwareBuffer.close() } catch (_: Exception) {}
                        screenshotBusy = false
                        handler.post { if (running) stopAutomation("Не удалось прочитать экран") }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    screenshotBusy = false
                    lastOcrAt = System.currentTimeMillis()
                    handler.post { if (::status.isInitialized) status.text = "Снимок экрана недоступен ($errorCode)"; if (running) stopAutomation("Нет доступа к снимку экрана") }
                }
            })
        } catch (_: Exception) {
            screenshotBusy = false
            if (running) stopAutomation("Ошибка снимка экрана")
        }
    }

    private fun doTap(max: Int) {
        val x = xInput.text.toString().toIntOrNull()
        val y = yInput.text.toString().toIntOrNull()
        if (x == null || y == null || x < 0 || y < 0 || x >= resources.displayMetrics.widthPixels || y >= resources.displayMetrics.heightPixels) {
            stopAutomation("Неверные координаты X/Y"); return
        }
        val delay = delayInput.text.toString().toLongOrNull()?.coerceIn(500L, 30_000L) ?: 1500L
        tap(x, y) {
            taps++
            status.text = "РАБОТАЕТ • $taps/$max • редких: $rareHits"
            handler.postDelayed(loop, delay)
        }
    }

    override fun onInterrupt() { stopAutomation("Служба прервана") }

    private fun showOverlay() {
        if (overlay != null) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xF21B2030.toInt()) }
        val title = TextView(this).apply { text = "NUMBER AI  ⋮  перетащи заголовок"; textSize = 14f; setTextColor(Color.WHITE); setPadding(0,0,0,6) }
        title.setOnTouchListener(object : View.OnTouchListener {
            var sx=0; var sy=0; var ix=0; var iy=0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = overlay?.layoutParams as? WindowManager.LayoutParams ?: return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX.toInt(); sy=e.rawY.toInt(); ix=lp.x; iy=lp.y; return true }
                    MotionEvent.ACTION_MOVE -> { lp.x=ix+e.rawX.toInt()-sx; lp.y=iy+e.rawY.toInt()-sy; (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(overlay,lp); return true }
                }; return true
            }
        })
        box.addView(title)
        status = TextView(this).apply { text="ОЖИДАНИЕ"; textSize=12f; setTextColor(0xFF86EFAC.toInt()); setPadding(0,4,0,8) }; box.addView(status)
        xInput = field("X координата", prefs.getInt("x", 540).toString())
        yInput = field("Y координата", prefs.getInt("y", 900).toString())
        delayInput = field("Интервал 500–30000 мс", prefs.getLong("delay", 1500L).toString())
        maxInput = field("Максимум нажатий", prefs.getInt("max", 100).toString())
        box.addView(xInput); box.addView(yInput); box.addView(delayInput); box.addView(maxInput)
        repeatEnabled = CheckBox(this).apply { text="Автогенерация включена"; setTextColor(Color.WHITE); isChecked=prefs.getBoolean("enabled", true) }; box.addView(repeatEnabled)
        pauseRare = CheckBox(this).apply { text="Останавливать при редком номере"; setTextColor(Color.WHITE); isChecked=prefs.getBoolean("pauseRare", true) }; box.addView(pauseRare)
        ocrEnabled = CheckBox(this).apply { text="OCR: распознавать экран (Android 11+)"; setTextColor(Color.WHITE); isChecked=prefs.getBoolean("ocr", true); isEnabled=Build.VERSION.SDK_INT>=30 }; box.addView(ocrEnabled)
        box.addView(Button(this).apply { text="🔎 ПРОВЕРИТЬ ЭКРАН"; setOnClickListener { if (activePackage != gamePackage) status.text="Сначала открой игру" else if (Build.VERSION.SDK_INT<30) status.text="Для OCR нужен Android 11+" else { status.text="Читаю экран…"; requestScreenshot(autoContinue = false) } } })
        box.addView(Button(this).apply { text="▶ СТАРТ"; setOnClickListener {
            saveSettings()
            if (activePackage != gamePackage) { status.text="Сначала открой игру и вернись к ней"; return@setOnClickListener }
            if (ocrEnabled.isChecked && Build.VERSION.SDK_INT<30) { status.text="Отключи OCR или используй Android 11+"; return@setOnClickListener }
            if (!running) { taps=0; lastResultSignature=""; lastOcrAt=0L; running=true; status.text="ЗАПУСК…"; if (ocrEnabled.isChecked) requestScreenshot(autoContinue = true) else handler.post(loop) }
        } })
        box.addView(Button(this).apply { text="■ СТОП ВСЁ"; setOnClickListener { stopAutomation("Остановлено вручную") } })
        box.addView(Button(this).apply { text="СОХРАНИТЬ НАСТРОЙКИ"; setOnClickListener { saveSettings(); status.text="Настройки сохранены" } })
        box.addView(TextView(this).apply { text="OCR использует встроенный распознаватель ML Kit. Проверяй результаты: распознавание редкости не гарантирует сохранность номера. Автопродажа не реализована."; textSize=10f; setTextColor(0xFFD1D5DB.toInt()); setPadding(0,8,0,0) })
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.START; x=12; y=120 }
        overlay=box
        try { wm.addView(box,params) } catch (_: Exception) { overlay=null }
    }

    private fun field(h: String, v: String) = EditText(this).apply { hint=h; setText(v); textSize=12f; setSingleLine(true); inputType=android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); setHintTextColor(0xFF9CA3AF.toInt()) }
    private fun saveSettings() {
        prefs.edit().putInt("x", xInput.text.toString().toIntOrNull() ?: 540).putInt("y", yInput.text.toString().toIntOrNull() ?: 900)
            .putLong("delay", delayInput.text.toString().toLongOrNull()?.coerceIn(500L,30000L) ?: 1500L)
            .putInt("max", maxInput.text.toString().toIntOrNull()?.coerceIn(1,1_000_000) ?: 100)
            .putBoolean("enabled", repeatEnabled.isChecked).putBoolean("pauseRare", pauseRare.isChecked).putBoolean("ocr", ocrEnabled.isChecked).apply()
    }
    private fun tap(x: Int, y: Int, done: () -> Unit) {
        if (waitingForGesture) { stopAutomation("Нельзя отправить два жеста одновременно"); return }
        waitingForGesture = true
        handler.removeCallbacks(gestureTimeout); handler.postDelayed(gestureTimeout, 3000L)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,65)).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { handler.removeCallbacks(gestureTimeout); waitingForGesture=false; if (running) done() }
            override fun onCancelled(gestureDescription: GestureDescription?) { handler.removeCallbacks(gestureTimeout); waitingForGesture=false; handler.post { if (running) stopAutomation("Нажатие отменено") } }
        }, handler)
        if (!accepted) { handler.removeCallbacks(gestureTimeout); waitingForGesture=false; stopAutomation("Android отклонил жест") }
    }
    private fun stopAutomation(reason: String) {
        running=false; handler.removeCallbacks(loop); handler.removeCallbacks(gestureTimeout); waitingForGesture=false
        if (::status.isInitialized) status.text="$reason • нажатий: $taps • совпадений: $rareHits"
    }
    override fun onDestroy() {
        stopAutomation("Служба выключена")
        try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (_: Exception) {}
        overlay=null
        try { recognizer.close() } catch (_: Exception) {}
        ocrExecutor.shutdownNow()
        super.onDestroy()
    }
}
