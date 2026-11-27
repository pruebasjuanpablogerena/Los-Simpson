package com.example.mislinks

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Bloqueador interno de anuncios, ventanas emergentes y redirecciones.
 *
 * - Bloquea peticiones a redes de anuncios conocidas y a dominios sospechosos.
 * - Bloquea redirecciones a otros sitios y esquemas raros (intent://, market://, etc.).
 * - Inyecta un script (en la página y en sus iframes) que anula window.open y
 *   quita el recuadro "Haz clic en este botón" con el regalo.
 *
 * Desde el panel 🛡 de la app se puede bloquear o permitir cualquier dominio.
 */
object AdBlocker {

    private val BLOCKED_HOSTS = setOf(
        // Google / grandes redes
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "google-analytics.com", "googletagmanager.com",
        "googletagservices.com", "adnxs.com", "adsrvr.org", "taboola.com",
        "outbrain.com", "criteo.com", "amazon-adsystem.com", "facebook.net",
        // Redes típicas de popups / popunders / redirecciones
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com",
        "adsterra.com", "highperformanceformat.com", "highrevenuegate.com",
        "highrevenuenetwork.com", "profitablegatecpm.com", "profitabledisplaynetwork.com",
        "effectivegatecpm.com", "effectivemeasure.com", "revenuehits.com",
        "clickadu.com", "hilltopads.net", "hilltopads.com", "richpush.co",
        "pushwhy.com", "onclickads.net", "onclkds.com", "clickaine.com",
        "adcash.com", "a-ads.com", "ad-maven.com", "admaven.com",
        "mgid.com", "revcontent.com", "bidvertiser.com", "zeropark.com",
        "trafficjunky.com", "trafficstars.com", "tsyndicate.com", "tsyndicate.net",
        "yllix.com", "ylx-aff.com", "adskeeper.com", "popunder.net",
        "monetag.com", "pubfuture.com", "pubadx.com", "adspyglass.com",
        "ero-advertising.com", "plugrush.com", "evadav.com", "dolohen.com",
        "acscdn.com", "adk2x.com", "bongacams.com", "chaturbate.com",
        "livejasmin.com", "stripchat.com", "cam4.com", "adultfriendfinder.com",
        "betway.com", "1xbet.com", "bet365.com", "melbet.com",
        "mostbet.com", "parimatch.com", "1win.com",
        // Dominios de anuncios vistos en la página (popups, falsos botones "Reproducir/Descargar")
        "secutorlunts.cfd", "subduepaler.cyou", "sxlzbz.com", "thruvhosts.click",
        "tryedesign.com", "vaganticrumbed.com", "withingnapperparols.cyou",
        "zymicweanie.com", "eyreknagged.rest", "chaldeedaws.com",
        "arctiidunction.rest", "mangepummelsumper.cyou", "offlapwolds.rest",
        "darnobedienceupscale.com", "oqdwahriht.in", "kindor.io",
        // Analítica (no hace falta para ver el video)
        "cloudflareinsights.com", "jwpltx.com"
    )

    private val BLOCKED_PATH_HINTS = listOf(
        "/ads/", "/adserver", "/adframe", "/banner_ads", "/popunder",
        "/popup.js", "adsbygoogle", "/pagead/", "/showad", "/pop.js"
    )

    /**
     * Dominios a los que SÍ se permite navegar en la ventana principal aunque sean
     * distintos al del link. Son los servidores de video de la página.
     */
    private val ALLOWED_NAV_DOMAINS = setOf("watchfun.top", "nupload.me", "nupload.my")

    /** Sitios de video que se "confían" durante la sesión (la cadena de saltos del servidor elegido). */
    private val trustedNav: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var lastTrustedNav = 0L

    fun isAllowedNav(host: String?): Boolean =
        matches(ALLOWED_NAV_DOMAINS, host) || matches(customAllowed, host) || matches(trustedNav, host)

    fun noteTrustedNav() {
        lastTrustedNav = android.os.SystemClock.uptimeMillis()
    }

    fun trust(host: String) {
        trustedNav.add(host.lowercase())
        noteTrustedNav()
    }

    /** ¿Estamos dentro de los primeros 10 segundos de un salto hacia un sitio de video confiable? */
    fun inTrustedChain(): Boolean =
        android.os.SystemClock.uptimeMillis() - lastTrustedNav < 10_000

    /** Dominios que la persona bloqueó desde el panel 🛡 (se guardan en el teléfono). */
    val customBlocked: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Dominios que la persona permitió desde el panel 🛡 (ganan sobre la lista base). */
    val customAllowed: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Dominios de terceros que la página ha contactado (para mostrarlos en el panel 🛡). */
    val seenThirdParty: MutableSet<String> = java.util.Collections.synchronizedSet(sortedSetOf<String>())

    /** Si es true, no se bloquea nada (prueba rápida desde el panel 🛡). Se reinicia al cerrar la app. */
    @Volatile
    var paused: Boolean = false

    /** Resultado ya calculado por dominio: evita recalcular en cada petición (clave en TV box lentos). */
    private val hostCache = ConcurrentHashMap<String, Boolean>()

    private const val PREFS = "mislinks_adblock"
    private const val KEY_BLOCKED = "custom_blocked"
    private const val KEY_ALLOWED = "custom_allowed"
    private const val KEY_TLD = "tld_bias"
    private const val KEY_STRIKES = "strikes"
    private var appCtx: android.content.Context? = null

    fun loadCustom(context: android.content.Context) {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        customBlocked.clear()
        customBlocked.addAll(p.getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet())
        customAllowed.clear()
        customAllowed.addAll(p.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet())
        appCtx = context.applicationContext
        strikes.clear()
        for (e in p.getStringSet(KEY_STRIKES, emptySet()) ?: emptySet()) {
            val k = e.substringBefore('=')
            val v = e.substringAfter('=', "").toIntOrNull() ?: continue
            if (k.isNotEmpty()) strikes[k] = v.coerceIn(0, 9)
        }
        tldBias.clear()
        for (e in p.getStringSet(KEY_TLD, emptySet()) ?: emptySet()) {
            val k = e.substringBefore('=')
            val v = e.substringAfter('=', "").toIntOrNull() ?: continue
            if (k.startsWith(".")) tldBias[k] = v.coerceIn(-3, 3)
        }
        hostCache.clear()
    }

    fun saveCustom(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_BLOCKED, HashSet(customBlocked))
            .putStringSet(KEY_ALLOWED, HashSet(customAllowed))
            .putStringSet(KEY_TLD, tldBias.map { "${it.key}=${it.value}" }.toSet())
            .putStringSet(KEY_STRIKES, strikes.map { "${it.key}=${it.value}" }.toSet())
            .apply()
        hostCache.clear()
    }

    private fun matches(set: Set<String>, host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return synchronized(set) { set.any { h == it || h.endsWith(".$it") } }
    }

    /** Dominio base simple: "www.sub.sitio.com" -> "sitio.com" (maneja co.uk, com.co, etc.). */
    fun baseDomain(host: String): String {
        val parts = host.lowercase().split(".").filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val secondLevel = setOf("com", "co", "net", "org", "gov", "edu")
        return if (parts.last().length == 2 && parts[parts.size - 2] in secondLevel) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    /** Rastreadores y analítica (nunca hacen falta para ver el video). */
    private val TRACKER_HOSTS = setOf(
        "hotjar.com", "hotjar.io", "clarity.ms", "mixpanel.com", "segment.io", "segment.com",
        "amplitude.com", "fullstory.com", "mouseflow.com", "crazyegg.com", "luckyorange.com",
        "nr-data.net", "newrelic.com", "scorecardresearch.com", "quantserve.com", "chartbeat.com",
        "chartbeat.net", "mc.yandex.ru", "metrika.yandex.ru", "analytics.tiktok.com", "bat.bing.com",
        "omtrdc.net", "demdex.net", "krxd.net", "bluekai.com", "addthis.com", "sharethis.com",
        "histats.com", "statcounter.com", "counter.yadro.ru", "clicky.com", "hm.baidu.com",
        "cnzz.com", "51.la", "umeng.com", "pixel.facebook.com", "connect.facebook.net",
        "stats.wp.com", "pingdom.net", "sentry.io", "bugsnag.com", "datadoghq.com", "heap.io",
        "heapanalytics.com", "intercom.io", "adjust.com", "appsflyer.com", "branch.io",
        "onesignal.com", "pushengage.com", "pushnami.com", "webpush.net"
    )

    /** Criptomineros y dominios de malware conocidos. */
    private val MINER_HOSTS = setOf(
        "coinhive.com", "coin-hive.com", "jsecoin.com", "cryptoloot.pro", "webmine.pro",
        "minero.cc", "authedmine.com", "crypto-loot.com", "coinimp.com", "ppoi.org",
        "monerominer.rocks", "minemytraffic.com", "mine.nahnah.ga"
    )

    private fun isKnownBad(host: String?): Boolean =
        matches(BLOCKED_HOSTS, host) || matches(TRACKER_HOSTS, host) || matches(MINER_HOSTS, host)

    private val TRACK_PARAMS = setOf("fbclid", "gclid", "dclid", "msclkid", "yclid", "igshid",
        "gbraid", "wbraid", "ttclid", "twclid", "mc_eid", "_ga", "_gl")

    /** Quita parámetros de rastreo (utm_*, fbclid, gclid...) de un enlace; null si no tenía. */
    fun stripTracking(u: android.net.Uri): android.net.Uri? {
        if (!u.isHierarchical || u.encodedQuery.isNullOrEmpty()) return null
        val names = u.queryParameterNames
        fun bad(n: String): Boolean { val l = n.lowercase(); return l in TRACK_PARAMS || l.startsWith("utm_") }
        if (names.none { bad(it) }) return null
        val b = u.buildUpon().clearQuery()
        for (n in names) if (!bad(n)) for (v in u.getQueryParameters(n)) b.appendQueryParameter(n, v)
        return b.build()
    }

    /** Terminaciones baratas que usan casi solo las redes de anuncios con dominios aleatorios. */
    private val SPAM_TLDS = setOf(".rest", ".cyou", ".cfd", ".click", ".sbs", ".icu", ".bond", ".quest", ".monster", ".autos")

    /** Terminaciones poco comunes: sospecha leve (suma menos puntos). */
    private val ODD_TLDS = setOf(".in", ".xyz", ".top", ".lat", ".site", ".online", ".live", ".pw", ".cc",
        ".tk", ".ml", ".ga", ".club", ".shop", ".fun", ".space", ".store", ".website", ".buzz", ".vip", ".win")

    /** Infraestructura común que nunca es publicidad. */
    private val SAFE_INFRA = setOf("googleapis.com", "gstatic.com", "cloudflare.com", "jsdelivr.net",
        "cdnjs.com", "bootstrapcdn.com", "jquery.com", "unpkg.com", "fontawesome.com", "gravatar.com")

    /** Puntaje a partir del cual un sitio desconocido se considera publicidad. */
    const val THRESHOLD = 5

    /** Sitio de la página principal (lo pone la pantalla al iniciar). */
    @Volatile
    var startBase: String = ""

    /** Lo único de otros sitios que la página principal necesita (fuentes, imágenes, reproductores). */
    private val TOP_ALLOW = setOf("cnddim.xyz", "repelisplus.lat", "repelisplus.my", "voe.sx",
        "tmdb.org", "themoviedb.org", "imgur.com", "ytimg.com", "youtube.com", "youtube-nocookie.com",
        "google.com", "recaptcha.net", "gravatar.com")

    /** Sitios no reconocidos que la página principal intentó cargar (se muestran en el panel). */
    val topDenied: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Modo estricto para la página principal: sus scripts, imágenes y estilos solo pueden venir
     * de una lista corta. Así los anuncios con dominios nuevos no pueden ni cargar.
     * No afecta a los reproductores (iframes), que siguen con el detector normal.
     */
    fun isUnknownForTop(host: String?, referer: String?): Boolean {
        if (paused || host.isNullOrEmpty() || referer.isNullOrEmpty() || startBase.isEmpty()) return false
        val refHost = android.net.Uri.parse(referer).host ?: return false
        val rb = baseDomain(refHost)
        if (rb != startBase && !rb.startsWith("repelisplus.")) return false
        val b = baseDomain(host)
        if (b == startBase || b.startsWith("repelisplus.")) return false
        if (matches(ALLOWED_NAV_DOMAINS, host) || matches(TOP_ALLOW, host) || matches(SAFE_INFRA, host) ||
            matches(customAllowed, host) || mediaHosts.contains(b) || isAllowedNav(host)) return false
        topDenied.add(b)
        return true
    }

    /** Intentos de ventana emergente / salto forzado por sitio (cada uno suma puntos). */
    private val strikes = ConcurrentHashMap<String, Int>()

    /** Lo que la app aprendió de tus toques en el panel: terminación -> ajuste de puntaje. */
    private val tldBias = ConcurrentHashMap<String, Int>()

    /** Servidores que ya entregaron video: se dejan pasar siempre (detector automático). */
    private val mediaHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Un sitio intentó abrir una ventana o forzar un salto: sube su puntaje. */
    fun noteStrike(host: String?) {
        if (host.isNullOrEmpty()) return
        val b = baseDomain(host)
        strikes[b] = minOf(9, (strikes[b] ?: 0) + 3)
        hostCache.clear()
        appCtx?.let { saveCustom(it) }
    }

    /** Aprende de la decisión de la persona: ajusta el peso de la terminación del dominio. */
    fun learn(host: String, blocked: Boolean) {
        val tld = "." + host.lowercase().substringAfterLast('.')
        val cur = tldBias[tld] ?: 0
        tldBias[tld] = (cur + if (blocked) 1 else -1).coerceIn(-3, 3)
        hostCache.clear()
    }

    /** Puntaje de sospecha de un dominio (más alto = más parece publicidad). */
    fun score(host: String): Int {
        val h = host.lowercase()
        val b = baseDomain(h)
        val tld = "." + b.substringAfterLast('.')
        val label = b.substringBeforeLast('.')
        var s = 0
        if (tld in SPAM_TLDS) s += 3 else if (tld in ODD_TLDS) s += 2
        s += when { label.length >= 14 -> 2; label.length >= 10 -> 1; else -> 0 }
        val letters = label.filter { it.isLetter() }
        if (letters.length >= 7) {
            val vowels = letters.count { it in "aeiou" }
            if (vowels * 100 / letters.length < 34) s += 2
            if (Regex("[^aeiou]{4,}").containsMatchIn(letters)) s += 2
        }
        if (label.any { it.isDigit() } && label.any { it.isLetter() }) s += 1
        if (startBase.isNotEmpty() && b != startBase) s += 1
        s += strikes[b] ?: 0
        s += tldBias[tld] ?: 0
        return s
    }

    private fun decide(h: String): Pair<Boolean, String> {
        val b = baseDomain(h)
        return when {
            matches(customAllowed, h) -> false to "Lo permitiste tú"
            matches(customBlocked, h) -> true to "Lo bloqueaste tú"
            matches(MINER_HOSTS, h) -> true to "Criptominero / malware"
            matches(TRACKER_HOSTS, h) -> true to "Rastreador"
            matches(BLOCKED_HOSTS, h) -> true to "Red de anuncios conocida"
            matches(ALLOWED_NAV_DOMAINS, h) -> false to "Servidor de video"
            mediaHosts.contains(b) -> false to "Entrega video"
            isAllowedNav(h) -> false to "Servidor de video"
            matches(SAFE_INFRA, h) -> false to "Servicio común"
            else -> {
                val sc = score(h)
                if (sc >= THRESHOLD) true to "Sospechoso (puntaje $sc)" else false to "Sitio externo"
            }
        }
    }

    fun isBlockedHost(host: String?): Boolean {
        if (paused || host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return hostCache.getOrPut(h) { decide(h).first }
    }

    /** Veredicto y motivo, para mostrar en el panel 🛡. */
    fun verdict(host: String): Pair<Boolean, String> {
        val h = host.lowercase()
        if (!paused && topDenied.contains(baseDomain(h)) && !matches(customAllowed, h) &&
            !matches(ALLOWED_NAV_DOMAINS, h) && !mediaHosts.contains(baseDomain(h))) {
            return true to "No reconocido en la página"
        }
        return decide(h)
    }

    private val MEDIA_EXT = listOf(".m3u8", ".mp4", ".webm", ".mpd", ".m4s", ".ts", ".m4v", ".mkv", ".key", ".vtt", ".srt")

    /** ¿Parece un pedido de video (lista HLS, segmento, mp4...) y no un anuncio? */
    fun looksLikeMedia(url: String, hasRange: Boolean): Boolean {
        if (hasRange) return true
        val path = url.substringBefore('?').lowercase()
        if (MEDIA_EXT.any { path.endsWith(it) }) return true
        return path.contains("/hls/") || path.contains("/playlist") || path.contains("/master.")
    }

    fun isBlockedUrl(url: String, host: String?, hasRange: Boolean = false): Boolean {
        if (paused) return false
        // Detector automático: lo que se ve como video de un sitio que no es red de anuncios conocida, pasa
        if (!host.isNullOrEmpty() && looksLikeMedia(url, hasRange) &&
            !isKnownBad(host) && !matches(customBlocked, host)) {
            val h = host.lowercase()
            if (mediaHosts.add(baseDomain(h))) hostCache.clear()
            return false
        }
        if (isBlockedHost(host)) return true
        if (matches(customAllowed, host)) return false
        val lower = url.lowercase()
        return BLOCKED_PATH_HINTS.any { lower.contains(it) }
    }

    /** Respuesta vacía para reemplazar lo bloqueado. */
    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /**
     * Script que se inyecta al inicio de cada documento (página principal e iframes).
     * Es liviano a propósito: no recorre la página en bucle, solo mira los elementos
     * nuevos que se van agregando (importante para que no se trabe en un TV box).
     */
    val INJECTED_JS = """
        (function() {
            if (window.__misLinksBlock) return;
            window.__misLinksBlock = true;
            try { window.open = function() { return null; }; } catch (e) {}

            // Antirrastreo: privacidad global, sin balizas ni batería, y huella de canvas alterada
            try { Object.defineProperty(navigator, 'globalPrivacyControl', { get: function() { return true; } }); } catch (e) {}
            try { Object.defineProperty(navigator, 'doNotTrack', { get: function() { return '1'; } }); } catch (e) {}
            try { if (navigator.sendBeacon) navigator.sendBeacon = function() { return true; }; } catch (e) {}
            try { if (navigator.getBattery) navigator.getBattery = function() { return Promise.reject(new Error('blocked')); }; } catch (e) {}
            try {
                var _td = HTMLCanvasElement.prototype.toDataURL;
                var _gi = CanvasRenderingContext2D.prototype.getImageData;
                HTMLCanvasElement.prototype.toDataURL = function() {
                    try {
                        var n = this.width * this.height;
                        if (n > 0 && n <= 250000) {
                            var t = document.createElement('canvas');
                            t.width = this.width; t.height = this.height;
                            var x = t.getContext('2d');
                            x.drawImage(this, 0, 0);
                            var d = _gi.call(x, 0, 0, 1, 1);
                            d.data[0] = d.data[0] ^ 1;
                            x.putImageData(d, 0, 0);
                            return _td.apply(t, arguments);
                        }
                    } catch (e) {}
                    return _td.apply(this, arguments);
                };
                CanvasRenderingContext2D.prototype.getImageData = function() {
                    var r = _gi.apply(this, arguments);
                    try { if (r.data.length >= 4) r.data[0] = r.data[0] ^ 1; } catch (e) {}
                    return r;
                };
            } catch (e) {}

            function addStyle() {
                try {
                    var css = '[id*="adsbygoogle"],[class*="adsbygoogle"],ins.adsbygoogle,' +
                              '[id^="google_ads"],[class*="ad-banner"],[class*="adbox"]' +
                              '{display:none !important; visibility:hidden !important;}';
                    var style = document.createElement('style');
                    style.innerHTML = css;
                    (document.head || document.documentElement).appendChild(style);
                } catch (e) {}
            }

            var re = /haz clic en este bot|descargar (aqu|v[i\u00ed]deo)|solicita tu plaza|d[o\u00f3]lares a la semana|hacer dinero|gran victoria|reproducir v[i\u00ed]deo.*(descargar|continuar)|^\s*continuar\s*$|new video available|click here to watch|watch now\.\.\.|is typing|alone today|join me|hookup|singles? (near|in)|\b(porn|xxx|nude|naked|horny|milf|webcam)\b|\b18\+/i;

            // Capas invisibles a pantalla completa que roban el primer toque para abrir anuncios
            function hijack(el) {
                try {
                    var tag = el.tagName;
                    if (tag === 'VIDEO' || tag === 'IFRAME' || tag === 'SCRIPT' || tag === 'STYLE' ||
                        tag === 'CANVAS' || tag === 'HTML' || tag === 'BODY' || tag === 'LINK') return;
                    var cs = getComputedStyle(el);
                    if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
                    var r = el.getBoundingClientRect();
                    if (r.width < window.innerWidth * 0.85 || r.height < window.innerHeight * 0.85) return;
                    if (el.querySelector && el.querySelector('video,canvas,iframe,input,button,svg,[role=button]')) return;
                    if ((el.textContent || '').trim().length > 3) return;
                    if (tag === 'A' || (el.querySelector && el.querySelector('a[href]'))) { el.remove(); }
                    else { el.style.setProperty('pointer-events', 'none', 'important'); }
                } catch (e) {}
            }

            // Dentro del reproductor: ningún enlace puede abrir otra pestaña ni llevar a otro sitio
            if (window.top !== window) {
                try {
                    document.addEventListener('click', function(ev) {
                        var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
                        if (!a) return;
                        var h = a.getAttribute('href') || '';
                        var ext = /^(https?:)?\/\//i.test(h) && a.host && a.host !== location.host;
                        if (a.target === '_blank' || ext) { ev.preventDefault(); ev.stopImmediatePropagation(); }
                    }, true);
                } catch (e) {}
            }

            // Quita el recuadro "Haz clic en este botón" con el regalo
            function check(el) {
                try {
                    if (!el || el.nodeType !== 1) return;
                    var t = el.textContent || '';
                    if (t.length === 0 || t.length > 400 || !re.test(t)) return;
                    var limit = window.innerWidth * window.innerHeight * 1.01;
                    var n = el;
                    while (n && n !== document.body && n !== document.documentElement) {
                        var pos = getComputedStyle(n).position;
                        if (pos === 'fixed' || pos === 'absolute') {
                            var r = n.getBoundingClientRect();
                            if (r.width * r.height < limit) { n.remove(); }
                            return;
                        }
                        n = n.parentElement;
                    }
                    // Sin capa flotante: quita el bloque pequeño que contiene el aviso
                    var m = el;
                    while (m.parentElement && m.parentElement !== document.body &&
                           (m.parentElement.textContent || '').length < 400) { m = m.parentElement; }
                    if (m !== document.body && m !== document.documentElement) m.remove();
                } catch (e) {}
            }

            var queue = [];
            var scheduled = false;
            function flush() {
                scheduled = false;
                var items = queue;
                queue = [];
                for (var i = 0; i < items.length && i < 150; i++) check(items[i]);
            }

            addStyle();
            try {
                new MutationObserver(function(muts) {
                    for (var i = 0; i < muts.length; i++) {
                        var added = muts[i].addedNodes;
                        for (var j = 0; j < added.length; j++) {
                            queue.push(added[j]);
                            if (added[j].nodeType === 1 && j < 10) hijack(added[j]);
                        }
                    }
                    if (!scheduled && queue.length) {
                        scheduled = true;
                        setTimeout(flush, 700);
                    }
                }).observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}
        })();
    """.trimIndent()


    /**
     * Navegación con control remoto (solo se inyecta en TV box / Google TV).
     * Las flechas saltan de botón en botón (temporadas, capítulos...) resaltando
     * el elemento con un borde amarillo, y OK lo selecciona. Si el elemento es el
     * reproductor (iframe), devuelve su posición para que la app active el cursor.
     */
    val TV_NAV_JS = """
        (function() {
            if (window.top !== window) return;
            if (window.__tvNav) { window.__tvNav('init'); return; }

            var CLS = '__tvf';
            var cur = null;
            var cache = null, cacheTime = 0, dirty = true;

            var st = document.createElement('style');
            st.textContent = '.' + CLS + '{outline:5px solid #FED90F !important;' +
                'outline-offset:3px !important;box-shadow:0 0 0 9px rgba(254,217,15,.30) !important;' +
                'border-radius:6px !important;}';
            (document.head || document.documentElement).appendChild(st);

            var SEL = 'a[href],button,[role=button],[role=tab],input:not([type=hidden]),select,iframe,[onclick],[tabindex]:not([tabindex="-1"])';

            function shown(el) {
                var cs = getComputedStyle(el);
                return cs.visibility !== 'hidden' && cs.display !== 'none' &&
                       cs.pointerEvents !== 'none' && cs.opacity !== '0';
            }

            // Lista de elementos que se pueden elegir (se recalcula solo si la página cambió)
            function build() {
                var out = [], i, el;
                var base = document.querySelectorAll(SEL);
                for (i = 0; i < base.length; i++) { if (shown(base[i])) out.push(base[i]); }
                // Botones que no son enlaces (div/li/span con cursor de mano)
                var all = document.querySelectorAll('div,li,span,label,p,td');
                var n = Math.min(all.length, 1500);
                for (i = 0; i < n; i++) {
                    el = all[i];
                    var cs = getComputedStyle(el);
                    if (cs.cursor !== 'pointer' || cs.visibility === 'hidden' ||
                        cs.display === 'none' || cs.pointerEvents === 'none') continue;
                    var par = el.parentElement;
                    if (par && getComputedStyle(par).cursor === 'pointer') continue;
                    if (el.closest(SEL)) continue;
                    out.push(el);
                }
                if (out.length < 400) {
                    out = out.filter(function(a) {
                        if (a.tagName === 'IFRAME') return true;
                        for (var j = 0; j < out.length; j++) {
                            if (out[j] !== a && a.contains(out[j]) && out[j].tagName !== 'IFRAME') return false;
                        }
                        return true;
                    });
                }
                return out;
            }

            function cands() {
                var now = Date.now();
                if (!cache || (dirty && now - cacheTime > 1200)) {
                    cache = build();
                    cacheTime = now;
                    dirty = false;
                }
                var vw = window.innerWidth, vh = window.innerHeight, out = [];
                for (var i = 0; i < cache.length; i++) {
                    var el = cache[i];
                    if (!el.isConnected) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width < 10 || r.height < 10) continue;
                    if (el.tagName !== 'IFRAME' && r.width * r.height > vw * vh * 0.8) continue;
                    out.push(el);
                }
                return out;
            }

            try {
                new MutationObserver(function() { dirty = true; })
                    .observe(document.documentElement, { childList: true, subtree: true });
            } catch (e) {}

            function txt(el) { return ((el.innerText || el.textContent || '') + '').trim(); }
            function numeric(el) { return /^\d{1,3}$/.test(txt(el)); }

            // Todas las temporadas visibles en una cuadrícula (sin la barra que se desplaza)
            function fixSeasons() {
                try {
                    var list = cands(), groups = [], i, k;
                    for (i = 0; i < list.length; i++) {
                        var el = list[i];
                        if (!numeric(el)) continue;
                        var row = el, d = 0;
                        while (row.parentElement && row.parentElement.children.length < 6 && d < 4) {
                            row = row.parentElement; d++;
                        }
                        var box = row.parentElement;
                        if (!box) continue;
                        var g = null;
                        for (k = 0; k < groups.length; k++) { if (groups[k].box === box) { g = groups[k]; break; } }
                        if (!g) { g = { box: box, items: [] }; groups.push(g); }
                        g.items.push(row);
                    }
                    var best = null;
                    for (k = 0; k < groups.length; k++) {
                        if (groups[k].items.length >= 6 && (!best || groups[k].items.length > best.items.length)) best = groups[k];
                    }
                    if (!best) return;
                    var b = best.box;
                    var P = function(node, prop, val) { node.style.setProperty(prop, val, 'important'); };
                    P(b, 'display', 'flex');
                    P(b, 'flex-wrap', 'wrap');
                    P(b, 'width', '100%');
                    P(b, 'min-width', '0');
                    P(b, 'max-width', '100%');
                    P(b, 'white-space', 'normal');
                    P(b, 'overflow', 'visible');
                    P(b, 'height', 'auto');
                    var n = b, depth = 0;
                    while (n && n !== document.body && depth < 6) {
                        if (getComputedStyle(n).overflowX !== 'visible') {
                            P(n, 'overflow', 'visible');
                            P(n, 'height', 'auto');
                            P(n, 'max-height', 'none');
                            P(n, 'max-width', '100%');
                            n.scrollLeft = 0;
                        }
                        n = n.parentElement; depth++;
                    }
                    dirty = true;
                } catch (e) {}
            }

            function center(r) { return { x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2 }; }

            function topLeft(list) {
                var best = null, bs = 1e12;
                for (var i = 0; i < list.length; i++) {
                    var r = list[i].getBoundingClientRect();
                    var s = r.top * 3 + r.left;
                    if (s < bs) { bs = s; best = list[i]; }
                }
                return best;
            }

            function initial(list) {
                var vh = window.innerHeight, vw = window.innerWidth, inView = [];
                for (var i = 0; i < list.length; i++) {
                    var el = list[i], r = el.getBoundingClientRect();
                    if (el.tagName === 'IFRAME' && r.width * r.height > vw * vh * 0.25) return el;
                    if (r.bottom >= 0 && r.top <= vh) inView.push(el);
                }
                return topLeft(inView) || list[0];
            }

            function langOf(t) {
                t = String(t || '').toLowerCase();
                if (/latino|\blat\b/.test(t)) return 1;
                if (/espa(\u00f1|n)ol|castellano|\bcast\b/.test(t)) return 2;
                return 0;
            }

            // Primer servidor en Latino; si no hay, el primero en Español. Devuelve null si no los reconoce.
            function langPick(list) {
                try {
                    var labels = [], tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
                    var n, i, j;
                    while ((n = tw.nextNode())) {
                        var v = (n.nodeValue || '').trim();
                        if (v.length > 0 && v.length < 40 && langOf(v) && n.parentElement && !n.parentElement.closest('a,button,[role=button]')) labels.push({ node: n, lang: langOf(v) });
                    }
                    if (!labels.length) return null;
                    var first = [null, null, null];
                    for (i = 0; i < list.length; i++) {
                        var el = list[i];
                        if (el.tagName === 'IFRAME' || numeric(el)) continue;
                        var t = txt(el);
                        if (t.length === 0 || t.length > 40) continue;
                        var lang = langOf(t.replace(/latino|espa(\u00f1|n)ol|castellano|[\s\-:|\u00b7]/gi, '').length < 2 ? '' : t);
                        if (!lang) {
                            for (j = labels.length - 1; j >= 0; j--) {
                                var ln = labels[j].node;
                                if (!(ln.compareDocumentPosition(el) & 4) || el.contains(ln)) continue;
                                var anc = ln.parentElement;
                                while (anc && !anc.contains(el)) anc = anc.parentElement;
                                if (anc && (anc.textContent || '').length < 400) lang = labels[j].lang;
                                break;
                            }
                        }
                        if (lang && !first[lang]) first[lang] = el;
                    }
                    return first[1] || first[2] || null;
                } catch (e) { return null; }
            }

            // Dónde empieza el foco: servidor Latino (o el primero en Español), temporada activa/primera, o lo primero visible
            function pref(list) {
                var i, el, servers = [], nums = [];
                var lp = langPick(list);
                if (lp) return lp;
                for (i = 0; i < list.length; i++) {
                    el = list[i];
                    var t = txt(el);
                    if (/^(nuuuppp|waaw)$/i.test(t)) servers.push(el);
                    else if (numeric(el)) nums.push(el);
                }
                if (servers.length) return topLeft(servers);
                if (nums.length >= 6) {
                    for (i = 0; i < nums.length; i++) {
                        el = nums[i];
                        var cls = String(el.className) + ' ' + (el.parentElement ? String(el.parentElement.className) : '');
                        if (/active|selected|current/i.test(cls) || el.getAttribute('aria-selected') === 'true') return el;
                    }
                    return topLeft(nums);
                }
                return initial(list);
            }

            function setCur(el) {
                if (cur) cur.classList.remove(CLS);
                cur = el;
                if (!el) return;
                el.classList.add(CLS);
                var r = el.getBoundingClientRect();
                try {
                    if (r.top < 60 || r.bottom > window.innerHeight - 60) {
                        el.scrollIntoView({ block: 'center', inline: 'center' });
                    } else if (r.left < 0 || r.right > window.innerWidth) {
                        el.scrollIntoView({ block: 'nearest', inline: 'center' });
                    }
                } catch (e) {}
            }

            function move(dir, retry) {
                var list = cands();
                if (!list.length) return false;
                if (!cur || !document.contains(cur) || list.indexOf(cur) < 0) {
                    setCur(pref(list));
                    return true;
                }
                var c = cur.getBoundingClientRect(), cc = center(c), best = null, bs = 1e12;
                for (var i = 0; i < list.length; i++) {
                    var el = list[i];
                    if (el === cur) continue;
                    var r = el.getBoundingClientRect(), p = center(r), gap, sec, ok = false;
                    if (dir === 'right') { ok = p.x > cc.x + 4; gap = r.left - c.right; sec = Math.abs(p.y - cc.y); }
                    else if (dir === 'left') { ok = p.x < cc.x - 4; gap = c.left - r.right; sec = Math.abs(p.y - cc.y); }
                    else if (dir === 'down') { ok = p.y > cc.y + 4; gap = r.top - c.bottom; sec = Math.abs(p.x - cc.x); }
                    else if (dir === 'up') { ok = p.y < cc.y - 4; gap = c.top - r.bottom; sec = Math.abs(p.x - cc.x); }
                    if (!ok) continue;
                    // Izquierda/derecha: solo elementos de la misma fila.
                    // Arriba/abajo: se prefieren los que quedan alineados en columna.
                    var overlap;
                    if (dir === 'left' || dir === 'right') {
                        overlap = r.top < c.bottom - c.height * 0.3 && r.bottom > c.top + c.height * 0.3;
                        if (!overlap) continue;
                    } else {
                        overlap = r.left < c.right && r.right > c.left;
                    }
                    var score = Math.max(gap, 0) + sec * 2.5 + (overlap ? 0 : 2000);
                    if (score < bs) { bs = score; best = el; }
                }
                if (best) { setCur(best); return true; }
                if (dir === 'down' || dir === 'up') {
                    var before = window.pageYOffset;
                    if (dir === 'up' && before <= 0) return 'top';
                    window.scrollBy(0, (dir === 'down' ? 1 : -1) * window.innerHeight * 0.6);
                    if (window.pageYOffset !== before && !retry) {
                        setTimeout(function() { move(dir, true); }, 150);
                    }
                }
                return false;
            }

            function ok() {
                if (!cur || !document.contains(cur)) {
                    var l = cands();
                    if (l.length) setCur(pref(l));
                    return '';
                }
                if (cur.tagName === 'IFRAME') {
                    var r = cur.getBoundingClientRect();
                    var d = window.devicePixelRatio || 1;
                    return 'iframe,' + Math.round((r.left + r.width / 2) * d) + ',' + Math.round((r.top + r.height / 2) * d);
                }
                try { if (cur.focus) cur.focus({ preventScroll: true }); } catch (e) {}
                cur.click();
                return 'click';
            }

            // Cualquier otra barra con desplazamiento horizontal: se muestra completa en varias filas
            function unwrap(list) {
                try {
                    var seen = [];
                    for (var i = 0; i < list.length; i++) {
                        var chain = [];
                        var p = list[i].parentElement, d = 0;
                        while (p && p !== document.body && p !== document.documentElement && d < 6) {
                            chain.push(p);
                            var cs = getComputedStyle(p);
                            if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll') &&
                                p.scrollWidth > p.clientWidth + 8) {
                                if (seen.indexOf(p) < 0) {
                                    seen.push(p);
                                    for (var q = 0; q < chain.length; q++) {
                                        var e = chain[q];
                                        e.style.setProperty('flex-wrap', 'wrap', 'important');
                                        e.style.setProperty('white-space', 'normal', 'important');
                                        e.style.setProperty('max-width', '100%', 'important');
                                        if (q < chain.length - 1) e.style.setProperty('width', 'auto', 'important');
                                    }
                                    p.style.setProperty('overflow-x', 'visible', 'important');
                                    p.style.setProperty('height', 'auto', 'important');
                                    p.style.setProperty('max-height', 'none', 'important');
                                    p.scrollLeft = 0;
                                }
                                break;
                            }
                            p = p.parentElement;
                            d++;
                        }
                    }
                } catch (e) {}
            }

            function init() {
                fixSeasons();
                unwrap(cands());
                var l = cands();
                if (l.length && (!cur || !document.contains(cur))) setCur(pref(l));
            }

            window.__tvNav = function(cmd) {
                if (cmd === 'init') { init(); return ''; }
                if (cmd === 'ok') return ok();
                var res = move(cmd, false);
                return res === 'top' ? 'top' : '';
            };

            setTimeout(init, 500);
            setTimeout(init, 1800);
            setTimeout(init, 3500);
        })();
    """.trimIndent()
}
