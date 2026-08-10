package kz.hh.resumebot

import org.json.JSONObject

/** Результат выполнения JS-клика на странице hh. */
data class RaiseResult(val found: Int, val clicked: Int, val texts: String)

/**
 * JavaScript, который ищет на странице кнопку «Поднять» /
 * «Поднять все резюме» / «Обновить дату» и нажимает её.
 *
 * Сознательно ищем по ТЕКСТУ кнопки, а не по CSS-классам:
 * hh часто меняет вёрстку, а текст кнопки стабилен.
 * Платные услуги («Продвижение», «Увеличить просмотры») отфильтровываем.
 */
object JsRaiser {

    /** Кликает все подходящие кнопки. Возвращает строку "F:<found>;C:<clicked>;T:<texts>". */
    val CLICK_JS: String = """
(function(){
  function vis(el){
    var r = el.getBoundingClientRect();
    var s = window.getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
  }
  var BAN = ['продвижени','продвинуть','увеличить просмотры','реклам','money','order','tariff'];
  var els = Array.prototype.slice.call(document.querySelectorAll('button, a, [role="button"]'));
  var cand = els.filter(function(el){
    if (!vis(el)) return false;
    var t = ((el.innerText||'')+' '+(el.getAttribute('title')||'')+' '+(el.getAttribute('aria-label')||''))
              .toLowerCase().replace(/\s+/g,' ').trim();
    if (!t || t.length > 40) return false;
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
  var clicked = 0, texts = [];
  inner.forEach(function(el){
    try { el.click(); clicked++; texts.push((el.innerText||'').trim().slice(0,25)); } catch(e){}
  });
  return 'F:' + inner.length + ';C:' + clicked + ';T:' + texts.join('|');
})()
""".trimIndent()

    /** Нажимает подтверждение в открывшемся диалоге («Поднять?» → «Да»). */
    val CONFIRM_JS: String = """
(function(){
  function vis(el){ var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  var scopes = document.querySelectorAll('[role="dialog"], [class*="modal"], [class*="Modal"], form');
  var n = 0;
  scopes.forEach(function(sc){
    sc.querySelectorAll('button, a').forEach(function(b){
      var t = (b.innerText||'').toLowerCase().trim();
      if (vis(b) && (t.indexOf('поднять') >= 0 || t === 'да' || t.indexOf('подтверд') >= 0)) {
        try { b.click(); n++; } catch(e){}
      }
    });
  });
  return 'C:' + n;
})()
""".trimIndent()

    /**
     * evaluateJavascript возвращает JSON-строку в кавычках — декодируем её,
     * затем разбираем протокол "F:..;C:..;T:..".
     */
    fun parse(raw: String?): RaiseResult {
        if (raw.isNullOrBlank() || raw == "null") return RaiseResult(0, 0, "")
        val decoded = try {
            JSONObject("{\"v\":$raw}").getString("v")
        } catch (e: Exception) {
            raw
        }
        var f = 0
        var c = 0
        var t = ""
        for (part in decoded.split(";")) {
            when {
                part.startsWith("F:") -> f = part.removePrefix("F:").toIntOrNull() ?: 0
                part.startsWith("C:") -> c = part.removePrefix("C:").toIntOrNull() ?: 0
                part.startsWith("T:") -> t = part.removePrefix("T:")
            }
        }
        return RaiseResult(f, c, t)
    }
}
