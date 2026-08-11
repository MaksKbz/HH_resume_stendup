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
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран: WebView со страницей hh, где лежат резюме
 * (сейчас это /applicant/profile/me, старый /applicant/resumes туда редиректит).
 *
 * Логика:
 *  1. Приложение открылось → проверяем время последнего поднятия:
 *     - прошло >= 4 часов (или поднятий не было) → поднимаем автоматически;
 *     - прошло меньше → просто показываем страницу и время следующего окна.
 *     Так приложение «помнит» поднятие и после перезагрузки телефона.
 *  2. Если не залогинены — hh покажет форму входа; входим ОДИН РАЗ.
 *  3. Кнопки «Поднять в поиске» жмём для КАЖДОГО резюме (по одному).
 *  4. Каждое событие пишется в текстовый лог (хранится до очистки).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var logText: TextView
    private lateinit var urlEdit: EditText

    private val handler = Handler(Looper.getMainLooper())

    /** Если true — после загрузки страницы с резюме жать «Поднять». */
    @Volatile
    private var autoClickPending = false

    companion object {
        /** Лимит hh: поднимать можно не чаще одного раза в 4 часа. */
        private const val RAISE_INTERVAL_MS = 4L * 60 * 60 * 1000

        const val URL_KZ = Prefs.URL_KZ
        const val URL_RU = Prefs.URL_RU
    }

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

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    override fun onDestroy() {
        try { webView.destroy() } catch (_: Throwable) { }
        super.onDestroy()
    }

    // ---------------------------------------------------------
    //  UI (программный, без XML)
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

        // --- Кнопки управления ---
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnRaise = Button(this).apply {
            text = "🔼 Поднять сейчас"
            setOnClickListener {
                // ручная команда — всегда пытаемся, игнорируя таймер 4 часов
                autoClickPending = true
                loadTarget()
            }
        }
        val btnLogin = Button(this).apply {
            text = "Войти в HH"
            setOnClickListener {
                autoClickPending = true
                loadTarget()
            }
        }
        row1.addView(btnRaise, weightLp(1f))
        row1.addView(btnLogin, weightLp(1f))
        head.addView(row1)

        // --- Авто-поднятие ---
        val autoSwitch = Switch(this).apply {
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

        // --- Тест фона (тумблер): пока включён — срабатывает каждые 15 минут ---
        val testSwitch = Switch(this).apply {
            text = "Тест фона: каждые 15 минут (вкл/выкл)"
            isChecked = Prefs.testMode(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setTestMode(this@MainActivity, checked)
                if (checked) {
                    RaiseWorker.scheduleTest(this@MainActivity)
                    Prefs.addLog(this@MainActivity, "тест", "тест-режим включён (каждые ~15 мин)")
                    status("Тест-режим включён: проверка каждые ~15 минут")
                } else {
                    RaiseWorker.cancelTest(this@MainActivity)
                    Prefs.addLog(this@MainActivity, "тест", "тест-режим выключен")
                    status("Тест-режим выключен")
                }
                refreshLog()
            }
        }
        head.addView(testSwitch)

        // --- Регион: KZ / RU ---
        val regionSwitch = Switch(this).apply {
            text = "Регион: hh.ru (Россия)  [выкл = hh.kz]"
            isChecked = Prefs.url(this@MainActivity).contains("hh.ru")
            setOnCheckedChangeListener { _, ru ->
                val newUrl = if (ru) URL_RU else URL_KZ
                Prefs.setUrl(this@MainActivity, newUrl)
                if (::urlEdit.isInitialized) urlEdit.setText(newUrl)
                Prefs.addLog(
                    this@MainActivity, "настройки",
                    if (ru) "регион переключён на hh.ru" else "регион переключён на hh.kz"
                )
                status("Регион: ${if (ru) "Россия (hh.ru)" else "Казахстан (hh.kz)"}. Загружаю…")
                autoClickPending = true
                loadTarget()
                refreshLog()
            }
        }
        head.addView(regionSwitch)

        // --- Своя ссылка ---
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlEdit = EditText(this).apply {
            setText(Prefs.url(this@MainActivity))
            textSize = 12f
            hint = Prefs.DEFAULT_URL
        }
        val btnUrl = Button(this).apply {
            text = "OK"
            textSize = 12f
            setOnClickListener {
                Prefs.setUrl(this@MainActivity, urlEdit.text.toString())
                status("Ссылка сохранена")
            }
        }
        row2.addView(urlEdit, weightLp(1f))
        row2.addView(btnUrl)
        head.addView(row2)

        // --- Лог: заголовок + очистка ---
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val logTitle = TextView(this).apply {
            text = "📋 Лог поднятий (вся история):"
            textSize = 12f
        }
        val btnClear = Button(this).apply {
            text = "🗑 Очистить"
            textSize = 12f
            setOnClickListener {
                Prefs.clearLog(this@MainActivity)
                refreshLog()
                status("Лог очищен")
            }
        }
        row3.addView(logTitle, weightLp(1f))
        row3.addView(btnClear)
        head.addView(row3)

        // --- Лог: текстовая область с прокруткой ---
        logText = TextView(this).apply {
            textSize = 11f
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val logBox = ScrollView(this)
        logBox.addView(logText)
        head.addView(
            logBox,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130))
        )

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
        refreshLog()
    }

    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                status("Загружено: ${url.take(60)}")
                // Кнопки поднятия есть на страницах личного кабинета
                // (/applicant/profile/me, старый /applicant/resumes и т.п.)
                // Страницу логина пропускаем: флаг сохранится, кликнем после входа.
                val isLoginPage = url.contains("login") || url.contains("account")
                if (autoClickPending && url.contains("/applicant/") && !isLoginPage) {
                    autoClickPending = false
                    view.postDelayed({ runRaise() }, 2000)
                }
            }
        }
    }

    /** Умный старт: поднимаем, только если с последнего успеха прошло >= 4 часов. */
    private fun handleIntent(i: Intent?) {
        val lastOk = Prefs.lastRaiseOk(this)
        val elapsed = System.currentTimeMillis() - lastOk

        if (lastOk > 0L && elapsed < RAISE_INTERVAL_MS) {
            val nextAt = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(lastOk + RAISE_INTERVAL_MS))
            status("Резюме уже подняты (лимит hh — раз в 4 часа). Следующее окно: ~$nextAt")
            autoClickPending = false
            loadTarget()
        } else {
            autoClickPending = true
            loadTarget()
        }
    }

    private fun loadTarget() {
        val url = Prefs.url(this)
        status("Открываю $url …")
        webView.loadUrl(url)
    }

    /** Поднимает ВСЕ доступные резюме на странице — по одному за раз. */
    private fun runRaise() {
        status("Ищу кнопки «Поднять в поиске»…")
        RaiseDriver.runChain(webView) { total, locked ->
            when {
                total > 0 -> {
                    status("✅ Готово! Поднято резюме: $total")
                    Prefs.setLastRaiseOk(this@MainActivity)
                    Prefs.addLog(this@MainActivity, "приложение", "поднято резюме: $total")
                    Notifier.notify(
                        this@MainActivity,
                        "✅ HH: поднято резюме: $total",
                        "Все доступные резюме подняты в поиске",
                        openApp = false
                    )
                    handler.postDelayed({
                        try { webView.reload() } catch (_: Throwable) { }
                    }, 1500)
                }
                locked > 0 -> {
                    status("✅ Все резюме уже подняты. Следующее поднятие будет доступно позже (лимит 4 ч).")
                    Prefs.addLog(
                        this@MainActivity, "приложение",
                        "все уже подняты (кнопок-замков: $locked)"
                    )
                }
                else -> {
                    status(
                        "⚠️ Кнопка «Поднять» не найдена. Если ниже форма входа — войдите. " +
                            "Если всё поднято — лимит 4 часа, подождите."
                    )
                    Prefs.addLog(this@MainActivity, "приложение", "кнопок нет (лимит 4 ч или вход)")
                    Notifier.notify(
                        this@MainActivity,
                        "HH: нечего поднимать",
                        "Кнопка не найдена: нужен вход или лимит 4 часов",
                        openApp = true
                    )
                }
            }
            refreshLog()
        }
    }

    private fun refreshLog() {
        if (::logText.isInitialized) {
            val log = Prefs.getLog(this)
            logText.text = if (log.isBlank()) "(лог пуст — события появятся после первого запуска)" else log
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
