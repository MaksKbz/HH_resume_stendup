package kz.hh.resumebot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Главный экран: WebView со страницей «Мои резюме» hh.kz.
 *
 * Логика:
 *  1. Приложение открылось → сразу грузим страницу с резюме.
 *  2. Если пользователь не залогинен — hh покажет форму входа,
 *     пользователь входит ОДИН РАЗ (сессия сохраняется в WebView).
 *  3. После загрузки страницы резюме автоматически жмём «Поднять».
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var autoSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())

    /** Если true — после загрузки страницы резюме нажать «Поднять». */
    @Volatile
    private var autoClickPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        setupWebView()
        requestNotifPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        try { webView.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }

    // ---------------------------------------------------------
    //  UI (программный, без XML — меньше файлов, проще сборка)
    // ---------------------------------------------------------

    private fun buildUi() {
        val pad = dp(10)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        statusView = TextView(this).apply {
            textSize = 14f
            text = "Запуск…"
        }
        head.addView(statusView)

        // Кнопки управления
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRaise = Button(this).apply {
            text = "🔼 Поднять сейчас"
            setOnClickListener {
                autoClickPending = true
                loadTarget()
            }
        }
        val btnLogin = Button(this).apply {
            text = "Войти в HH"
            setOnClickListener {
                // hh сам перекинет на форму входа; после входа кликнем автоматически
                autoClickPending = true
                loadTarget()
            }
        }
        row1.addView(btnRaise, weightLp(1f))
        row1.addView(btnLogin, weightLp(1f))
        head.addView(row1)

        // Переключатель авто-режима
        autoSwitch = Switch(this).apply {
            text = "Автоподнятие каждые 4 часа (в фоне)"
            isChecked = Prefs.auto(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAuto(this@MainActivity, checked)
                if (checked) {
                    RaiseWorker.schedule(this@MainActivity)
                    status("Автоподнятие включено: каждые ~4 часа")
                } else {
                    RaiseWorker.cancel(this@MainActivity)
                    status("Автоподнятие выключено")
                }
            }
        }
        head.addView(autoSwitch)

        // Поле ссылки (можно вставить свою страницу с кнопкой)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etUrl = EditText(this).apply {
            setText(Prefs.url(this@MainActivity))
            textSize = 12f
            hint = "https://hh.kz/applicant/resumes"
        }
        val btnUrl = Button(this).apply {
            text = "OK"
            textSize = 12f
            setOnClickListener {
                Prefs.setUrl(this@MainActivity, etUrl.text.toString())
                status("Ссылка сохранена")
            }
        }
        row2.addView(etUrl, weightLp(1f))
        row2.addView(btnUrl)
        head.addView(row2)

        // Telegram (необязательно)
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etToken = EditText(this).apply {
            setText(Prefs.tgToken(this@MainActivity))
            hint = "TG_BOT_TOKEN (необяз.)"
            textSize = 11f
        }
        val etChat = EditText(this).apply {
            setText(Prefs.tgChat(this@MainActivity))
            hint = "TG_CHAT_ID"
            textSize = 11f
        }
        val btnTg = Button(this).apply {
            text = "Сохр."
            textSize = 11f
            setOnClickListener {
                Prefs.setTgToken(this@MainActivity, etToken.text.toString())
                Prefs.setTgChat(this@MainActivity, etChat.text.toString())
                status("Telegram-настройки сохранены")
            }
        }
        row3.addView(etToken, weightLp(1.4f))
        row3.addView(etChat, weightLp(1f))
        row3.addView(btnTg)
        head.addView(row3)

        root.addView(
            head,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        webView = WebView(this)
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
    }

    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val short = url.take(60)
                status("Загружено: $short")
                if (autoClickPending && url.contains("/applicant/resumes")) {
                    autoClickPending = false
                    view.postDelayed({ runRaise("приложение") }, 2000)
                }
            }
        }
    }

    private fun handleIntent(i: Intent?) {
        // Главное требование: открыл приложение → оно само поднимает резюме
        autoClickPending = true
        loadTarget()
    }

    private fun loadTarget() {
        val url = Prefs.url(this)
        status("Открываю $url …")
        webView.loadUrl(url)
    }

    /** Нажимает «Поднять» на текущей странице и отчитывается о результате. */
    private fun runRaise(source: String) {
        status("Ищу кнопку «Поднять»…")
        webView.evaluateJavascript(JsRaiser.CLICK_JS) { raw ->
            val r = JsRaiser.parse(raw)
            when {
                r.clicked > 0 -> {
                    status("Нажато кнопок: ${r.clicked} (${r.texts})")
                    // возможный диалог подтверждения — подтверждаем
                    handler.postDelayed({
                        try { webView.evaluateJavascript(JsRaiser.CONFIRM_JS, null) } catch (_: Throwable) { }
                    }, 1200)
                    handler.postDelayed({
                        status("✅ Готово! Дата резюме обновлена (см. страницу ниже).")
                        try { webView.reload() } catch (_: Throwable) { }
                        Notifier.notify(
                            this@MainActivity,
                            "✅ HH: резюме подняты",
                            "Нажато кнопок: ${r.clicked}",
                            openApp = false
                        )
                        Telegram.send(
                            this@MainActivity,
                            "✅ HH: резюме подняты (кнопок: ${r.clicked}, через $source)"
                        )
                    }, 2500)
                }
                r.found > 0 ->
                    status("⏳ Кнопок найдено: ${r.found}, но нажать не вышло (лимит 4 часа?)")
                else -> {
                    status(
                        "⚠️ Кнопка «Поднять» не найдена. Если ниже форма входа — " +
                            "войдите, клик произойдёт автоматически."
                    )
                    Telegram.send(this@MainActivity, "⚠️ HH: кнопка «Поднять» не найдена ($source)")
                }
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    private fun status(s: String) {
        statusView.text = s
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun weightLp(w: Float) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
}
