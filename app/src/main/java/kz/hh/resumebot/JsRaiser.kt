package kz.hh.resumebot

import android.webkit.WebView
import org.json.JSONObject

/**
 * Результат выполнения JS на странице hh.
 * found   — сколько активных кнопок «Поднять» найдено
 * clicked — сколько нажато (0 или 1, кликаем по одной)
 * texts   — текст нажатой кнопки
 * locked  — сколько кнопок-«замков» вида «Поднять в 18:49» (ещё недоступны)
 */
data class RaiseResult(
    val found: Int,
    val clicked: Int,
    val texts: String,
    val locked: Int = 0
)

/**
 * JavaScript, который ищет на странице кнопку «Поднять в поиске» /
 * «Поднять» / «Обновить дату» и нажимает её (ровно ОДНУ за вызов).
 *
 * Сознательно ищем по ТЕКСТУ кнопки, а не по CSS-классам:
 * hh часто меняет вёрстку, а текст кнопки стабилен.
 * «Поднять в 18:49» (кнопка-замок до нужного времени) пропускаем.
 * Платные услуги («Продвижение», «Увеличить просмотры») не трогаем.
 */
object JsRaiser {

    /** Кликает ПЕРВУЮ доступную кнопку. Возвращает "F:..;C:..;L:..;T:..". */
    val CLICK_ONE_JS: String = """
(function(){
  function vis(el){
    var r = el.getBoundingClientRect();
    var s = window.getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  }
  function txt(el){
    return ((el.innerText||'')+' '+(el.getAttribute('title')||'')+' '+(el.getAttribute('aria-label')||''))
             .toLowerCase().replace(/\s+/g,' ').trim();
  }
  var BAN = ['продвижени','продвинуть','увеличить просмотры','реклам'];
  var LOCK = /поднять в \d{1,2}[:.]\d{2}/;
  var els = Array.prototype.slice.call(
    document.querySelectorAll('button, a, [role="button"], span')
  );
  // Кнопки-«замки» («Поднять в 18:49») — считаем, но не трогаем
  var lockEls = els.filter(function(el){ return vis(el) && LOCK.test(txt(el)); });
  var locked = lockEls.filter(function(el){
    return !lockEls.some(function(o){ return o !== el && el.contains(o); });
  }).length;

  var cand = els.filter(function(el){
    if (!vis(el)) return false;
    var t = txt(el);
    if (!t || t.length > 40) return false;
    if (LOCK.test(t)) return false;
    if (t.indexOf('поднять') < 0 && t.indexOf('обновить дату') < 0) return false;
    for (var i = 0; i < BAN.length; i++) { if (t.indexOf(BAN[i]) >= 0) return false; }
    var h = el.getAttribute('href') || '';
    if (/money|order|tariff|payment|price/.test(h)) return false;
    return true;
  });
  // Из вложенных друг в друга кандидатов оставляем самый внутренний
  var inner = cand.filter(function(el){
    return !cand.some(function(o){ return o !== el && el.contains(o); });
  });
  if (!inner.length) return 'F:0;C:0;L:' + locked + ';T:';
  var el = inner[0];
  var t = (el.innerText||'').trim().slice(0,25);
  try { el.click(); return 'F:' + inner.length + ';C:1;L:' + locked + ';T:' + t; }
  catch(e){ return 'F:' + inner.length + ';C:0;L:' + locked + ';T:err'; }
})()
""".trimIndent()

    /** Нажимает подтверждения в диалогах и закрывает всплывшие окна/апселлы. */
    val CONFIRM_JS: String = """
(function(){
  function vis(el){ var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  var n = 0;
  var scopes = document.querySelectorAll('[role="dialog"], [class*="modal"], [class*="Modal"], form');
  scopes.forEach(function(sc){
    sc.querySelectorAll('button, a').forEach(function(b){
      var t = (b.innerText||'').toLowerCase().trim();
      if (vis(b) && (t.indexOf('поднять') >= 0 || t === 'да' || t.indexOf('подтверд') >= 0)) {
        try { b.click(); n++; } catch(e){}
      }
    });
  });
  // Закрываем апселлы/модалки, чтобы не мешали следующим итерациям
  document.querySelectorAll(
    '[aria-label="Закрыть"], [class*="modal-close"], [class*="modal__close"], [data-qa*="close"]'
  ).forEach(function(b){
    if (vis(b)) { try { b.click(); } catch(e){} }
  });
  return 'C:' + n;
})()
""".trimIndent()

    /**
     * evaluateJavascript возвращает JSON-строку в кавычках — декодируем её,
     * затем разбираем протокол "F:..;C:..;L:..;T:..".
     */
    fun parse(raw: String?): RaiseResult {
        if (raw.isNullOrBlank() || raw == "null") return RaiseResult(0, 0, "", 0)
        val decoded = try {
            JSONObject("{\"v\":$raw}").getString("v")
        } catch (e: Exception) {
            raw
        }
        var f = 0
        var c = 0
        var l = 0
        var t = ""
        for (part in decoded.split(";")) {
            when {
                part.startsWith("F:") -> f = part.removePrefix("F:").toIntOrNull() ?: 0
                part.startsWith("C:") -> c = part.removePrefix("C:").toIntOrNull() ?: 0
                part.startsWith("L:") -> l = part.removePrefix("L:").toIntOrNull() ?: 0
                part.startsWith("T:") -> t = part.removePrefix("T:")
            }
        }
        return RaiseResult(f, c, t, l)
    }
}

/**
 * Цепочка поднятия для НЕСКОЛЬКИХ резюме:
 * нажимает одну кнопку → ждёт обновления страницы → заново ищет следующую.
 * После каждого клика hh перерисовывает список, поэтому работаем
 * только со свежей разметкой.
 * onDone получает (сколько нажато, сколько кнопок ещё «замкнуто» по времени).
 */
object RaiseDriver {

    private const val MAX_STEPS = 12
    private const val STEP_DELAY_MS = 1600L

    fun runChain(webView: WebView, onDone: (total: Int, locked: Int) -> Unit) {
        step(webView, MAX_STEPS, 0, onDone)
    }

    private fun step(
        webView: WebView,
        stepsLeft: Int,
        acc: Int,
        onDone: (total: Int, locked: Int) -> Unit
    ) {
        try {
            webView.evaluateJavascript(JsRaiser.CLICK_ONE_JS) { raw ->
                val r = JsRaiser.parse(raw)
                if (r.clicked > 0 && stepsLeft > 1) {
                    // возможный диалог подтверждения / апселл
                    try { webView.evaluateJavascript(JsRaiser.CONFIRM_JS, null) } catch (_: Throwable) { }
                    webView.postDelayed(
                        { step(webView, stepsLeft - 1, acc + r.clicked, onDone) },
                        STEP_DELAY_MS
                    )
                } else {
                    onDone(acc + r.clicked, r.locked)
                }
            }
        } catch (_: Throwable) {
            onDone(acc, 0)
        }
    }
}
