package com.numberai.gamebot

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFFF8FAFC.toInt())
        }
        root.addView(TextView(this).apply {
            text = "NUMBER AI"
            textSize = 28f
            setTextColor(0xFF111827.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Помощник для «Генератора номеров»\n\n1. Включи службу доступности.\n2. Открой игру.\n3. В панели Number AI задай координаты кнопки генерации.\n4. Нажми «Старт» только когда игра готова."
            textSize = 16f
            setTextColor(0xFF374151.toInt())
            setPadding(0, 16, 0, 20)
        })
        val access = Button(this).apply {
            text = "Открыть настройки доступности"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(access)
        val overlay = Button(this).apply {
            text = "Разрешить отображение поверх окон"
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= 23) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        root.addView(overlay)
        root.addView(TextView(this).apply {
            text = "\nЧто умеет прототип:\n• панель запуска/остановки автокликов;\n• анализ доступного игрового текста и OCR через ML Kit (Android 11+);\n• настраиваемые координаты и задержка.\n\nВажно: это прототип автоматизации, не полноценная визуальная ИИ. OCR пытается прочитать графический текст с экрана, но распознавание кириллических символов и редкости не гарантируется. При сомнении останови автоматизацию вручную."
            textSize = 14f
            setTextColor(0xFF4B5563.toInt())
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
