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
 * Главный экран: WebView со страницей hh, где лежат резюме
 * (сейчас это /applicant/profile/me, старый /applicant/resumes туда редиректит).
 *
 * Логика:
 *  1. Приложение открылось → сразу грузим страницу.
 *  2. Если не залогинены — hh покажет форму входа; входим ОДИН РАЗ
 *     (сессия сохраняется в WebView).
 *  3. После загрузки страницы с резюме автоматически жмём «Поднять в поиске»
 *     для КАЖДОГО доступного резюме (по одному, с перепоиском).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var autoSwitch: Switch

    private val handler = Handler(Looper.getMainLooper())

    /** Если true — после загрузки страницы с резюме жать «Поднять». */
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
            hint = Prefs.DEFAULT_URL
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

    /** Поднимает ВСЕ доступные резюме на странице — по одному за раз. */
    private fun runRaise() {
        status("Ищу кнопки «Поднять в поиске»…")
        RaiseDriver.runChain(webView) { total ->
            if (total > 0) {
                status("✅ Готово! Поднято резюме: $total")
                Notifier.notify(
                    this@MainActivity,
                    "✅ HH: поднято резюме: $total",
                    "Все доступные резюме подняты в поиске",
                    openApp = false
                )
                handler.postDelayed({
                    try { webView.reload() } catch (_: Throwable) { }
                }, 1500)
            } else {
                status(
                    "⚠️ Кнопка «Поднять» не найдена. Если ниже форма входа — войдите. " +
                        "Если всё поднято — лимит 4 часа, подождите."
                )
                Notifier.notify(
                    this@MainActivity,
                    "HH: нечего поднимать",
                    "Кнопка не найдена: нужен вход или лимит 4 часов",
                    openApp = true
                )
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
