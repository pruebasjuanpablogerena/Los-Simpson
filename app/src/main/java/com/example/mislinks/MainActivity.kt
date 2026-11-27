package com.example.mislinks

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Pantalla de inicio de la app: elegir temporada y capítulo (NO el reproductor). */
private const val HOME_URL = "https://repelisplus.my/ver/los-simpsons-hd-c-kjon/"

// Paleta inspirada en Los Simpson
private val SimpsonYellow = Color(0xFFFED90F)
private val NightPurple = Color(0xFF120A2A)
private val SurfacePurple = Color(0xFF231250)
private val SkyBlue = Color(0xFF3FA9F5)

private val AppColors = darkColorScheme(
    primary = SimpsonYellow,
    onPrimary = Color(0xFF1A1200),
    secondary = SkyBlue,
    background = NightPurple,
    onBackground = Color.White,
    surface = SurfacePurple,
    onSurface = Color.White
)

/**
 * Estado compartido para el modo control remoto (TV box / Google TV).
 *
 * Hay dos formas de usar el control:
 *  1. Navegación por botones (por defecto): las flechas saltan entre temporadas y
 *     capítulos, resaltados con borde amarillo. OK selecciona.
 *  2. Cursor: se activa solo al entrar al reproductor o a pantalla completa. Las flechas
 *     mueven un círculo en pantalla y OK hace clic. Atrás vuelve a la navegación por botones.
 */
object TvInput {
    var enabled: Boolean = false
    var fullscreen: Boolean = false
    var webView: WebView? = null
    var dialogOpen by mutableStateOf(false)
    var cursorMode by mutableStateOf(false)
    var cursorX by mutableFloatStateOf(0f)
    var cursorY by mutableFloatStateOf(0f)

    /** Salto a otro sitio que se bloqueó y que la persona puede permitir con OK / un toque. */
    var pendingNav by mutableStateOf<String?>(null)

    /** Último error HTTP de una página principal (se muestra en el panel para diagnóstico). */
    var lastError: String? = null
    var scriptHandler: ScriptHandler? = null

    fun installScript(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                wv, AdBlocker.INJECTED_JS, setOf("*")
            )
        }
    }

    /** Modo compatibilidad: sin bloqueo (salvo ventanas emergentes y descargas) y recarga la página. */
    fun setPaused(paused: Boolean) {
        AdBlocker.paused = paused
        val wv = webView ?: return
        scriptHandler?.remove()
        scriptHandler = null
        if (!paused) installScript(wv)
        wv.settings.mixedContentMode =
            if (paused) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        wv.reload()
    }

    /** Permite el sitio del salto bloqueado (queda guardado) y abre la página. */
    fun allowPendingNav(context: Context) {
        val target = pendingNav ?: return
        val host = Uri.parse(target).host
        if (host != null) {
            AdBlocker.customAllowed.add(AdBlocker.baseDomain(host))
            AdBlocker.saveCustom(context)
        }
        pendingNav = null
        webView?.loadUrl(target)
    }
}

class MainActivity : ComponentActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastNavTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barras del sistema transparentes con íconos claros (la app dibuja debajo)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // ¿Es un TV box / Google TV / dispositivo sin pantalla táctil?
        val ui = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        TvInput.enabled =
            ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val dm = resources.displayMetrics
        TvInput.cursorX = dm.widthPixels / 2f
        TvInput.cursorY = dm.heightPixels / 2f

        setContent {
            MaterialTheme(colorScheme = AppColors) {
                var showSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(2000)
                    showSplash = false
                }

                // La página se empieza a cargar debajo de la pantalla de inicio
                Box(modifier = Modifier.fillMaxSize().background(NightPurple)) {
                    WebViewScreen(url = HOME_URL)
                    if (showSplash) SplashScreen()
                    if (TvInput.enabled && TvInput.cursorMode && !showSplash && !TvInput.dialogOpen) {
                        TvCursor()
                    }
                }
            }
        }
    }

    // ---------- Control remoto ----------
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!TvInput.enabled || TvInput.dialogOpen) return super.dispatchKeyEvent(event)

        val code = event.keyCode
        val isDir = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        if (!isDir && !isOk) return super.dispatchKeyEvent(event)

        // Aviso "Se bloqueó un salto a...": OK lo permite
        if (isOk && TvInput.pendingNav != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                TvInput.allowPendingNav(applicationContext)
            }
            return true
        }

        // Modo cursor (reproductor / pantalla completa)
        if (TvInput.cursorMode || TvInput.fullscreen) {
            if (isDir && event.action == KeyEvent.ACTION_DOWN) moveCursor(code, event.repeatCount)
            if (isOk && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) clickAtCursor()
            return true
        }

        // Modo navegación por botones (temporadas y capítulos)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val wv = TvInput.webView ?: return true

        if (isDir) {
            // Si se mantiene la tecla, se limita la velocidad para no saturar el TV box
            if (event.repeatCount > 0 && event.eventTime - lastNavTime < 130) return true
            lastNavTime = event.eventTime
            val dir = when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                else -> "right"
            }
            wv.evaluateJavascript("window.__tvNav?window.__tvNav('$dir'):''") { raw ->
                if ((raw ?: "").trim('"') == "top") {
                    // Ya está arriba del todo: el cursor se coloca sobre el escudo 🛡 (OK abre el panel)
                    val dm = resources.displayMetrics
                    val w = window.decorView.width.takeIf { it > 0 } ?: dm.widthPixels
                    TvInput.cursorX = w - 56f * dm.density
                    TvInput.cursorY = 22f * dm.density
                    TvInput.cursorMode = true
                }
            }
        } else if (event.repeatCount == 0) {
            wv.evaluateJavascript("window.__tvNav?window.__tvNav('ok'):''") { raw ->
                val s = (raw ?: "").trim('"')
                if (s.startsWith("iframe,")) {
                    // OK sobre el reproductor: activa el cursor en el centro del video y toca
                    val p = s.split(",")
                    val px = p.getOrNull(1)?.toFloatOrNull()
                    val py = p.getOrNull(2)?.toFloatOrNull()
                    if (px != null && py != null) {
                        val loc = IntArray(2)
                        wv.getLocationInWindow(loc)
                        TvInput.cursorX = loc[0] + px
                        TvInput.cursorY = loc[1] + py
                        TvInput.cursorMode = true
                        clickAtCursor()
                    }
                }
            }
        }
        return true
    }

    private fun moveCursor(code: Int, repeat: Int) {
        val dm = resources.displayMetrics
        val w = window.decorView.width.takeIf { it > 0 } ?: dm.widthPixels
        val h = window.decorView.height.takeIf { it > 0 } ?: dm.heightPixels
        // Mantener la tecla acelera el cursor
        val step = (6f + minOf(repeat, 20) * 1.2f) * dm.density

        var x = TvInput.cursorX
        var y = TvInput.cursorY
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> x -= step
            KeyEvent.KEYCODE_DPAD_RIGHT -> x += step
            KeyEvent.KEYCODE_DPAD_UP -> y -= step
            KeyEvent.KEYCODE_DPAD_DOWN -> y += step
        }
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
        TvInput.cursorX = x
        TvInput.cursorY = y

        // Al llegar a los bordes, la página se desplaza sola
        val wv = TvInput.webView
        if (wv != null && !TvInput.fullscreen) {
            val edge = 70f * dm.density
            if (code == KeyEvent.KEYCODE_DPAD_UP && y < edge * 1.3f) wv.scrollBy(0, -(step * 2).toInt())
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && y > h - edge) wv.scrollBy(0, (step * 2).toInt())
        }
    }

    private fun clickAtCursor() {
        val x = TvInput.cursorX
        val y = TvInput.cursorY
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        dispatchTouchEvent(down)
        down.recycle()
        uiHandler.postDelayed({
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0
            )
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            dispatchTouchEvent(up)
            up.recycle()
        }, 70)
    }

    /**
     * Si la app deja de verse (botón inicio, apps recientes, bloqueo de pantalla...)
     * se cierra por completo: no queda nada en segundo plano.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            // Borra cookies, datos del sitio y caché: no queda rastro de lo que viste
            try {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
                TvInput.webView?.clearCache(true)
                TvInput.webView?.clearFormData()
            } catch (e: Exception) {}
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            // Termina también el proceso (sin servicios, audio ni video residual)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

@Composable
private fun TvCursor() {
    val r = with(LocalDensity.current) { 13.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((TvInput.cursorX - r).toInt(), (TvInput.cursorY - r).toInt()) }
            .size(26.dp)
            .background(Color.White.copy(alpha = 0.85f), CircleShape)
            .border(3.dp, SimpsonYellow, CircleShape)
    )
}

@Composable
fun SplashScreen() {
    // En horizontal (TV box / Google TV) se usa drawable-land-nodpi/splash.jpg
    Image(
        painter = painterResource(R.drawable.splash),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun SlimTopBar(blockedCount: Int, hint: String?, onShieldClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NightPurple)
            .statusBarsPadding()
            .height(46.dp)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "LOS SIMPSON",
            color = SimpsonYellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            modifier = if (hint == null) Modifier.weight(1f) else Modifier
        )
        if (hint != null) {
            Text(
                hint,
                color = Color(0xFFB9B0D6),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0x33FED90F))
                .border(1.dp, Color(0x66FED90F), RoundedCornerShape(50))
                .clickable { onShieldClick() }
                .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Icon(
                Icons.Filled.Shield,
                contentDescription = "Protección",
                tint = SimpsonYellow,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "$blockedCount",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun WebViewScreen(url: String) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context as? Activity

    var webView by remember { mutableStateOf<WebView?>(null) }
    var blockedCount by remember { mutableStateOf(0) }
    var showDomains by remember { mutableStateOf(false) }
    var domainsSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }

    // Pantalla completa del video
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val startBase = remember(url) { AdBlocker.baseDomain(Uri.parse(url).host ?: "") }

    // El contador se actualiza como máximo 1 vez por segundo (menos trabajo en TV box lentos)
    val pendingBlocked = remember { AtomicInteger(0) }
    val flushScheduled = remember { AtomicBoolean(false) }

    remember(url) {
        AdBlocker.loadCustom(appContext)
        AdBlocker.seenThirdParty.clear()
        AdBlocker.startBase = startBase
        true
    }

    fun setFullscreen(on: Boolean) {
        TvInput.fullscreen = on
        if (on && TvInput.enabled) TvInput.cursorMode = true
        val act = activity ?: return
        val controller = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (on) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun exitFullscreen() {
        customCallback?.onCustomViewHidden()
        customCallback = null
        customView = null
        setFullscreen(false)
    }

    // Historial propio de páginas: respaldo por si el WebView no tiene a dónde retroceder.
    val navStack = remember { mutableListOf<String>() }
    var lastBackAt by remember { mutableStateOf(0L) }

    // Botón "atrás": cierra avisos, sale de pantalla completa o del cursor y vuelve a la página
    // anterior. Solo cierra la app desde la página de inicio (pulsando dos veces).
    BackHandler {
        val wv = webView
        fun isHome(u: String?): Boolean =
            u == null || u.substringBefore('#').substringBefore('?').trimEnd('/') ==
                url.substringBefore('#').substringBefore('?').trimEnd('/')
        when {
            customView != null -> exitFullscreen()
            TvInput.enabled && TvInput.cursorMode -> TvInput.cursorMode = false
            TvInput.pendingNav != null -> TvInput.pendingNav = null
            wv != null && wv.canGoBack() -> {
                if (navStack.isNotEmpty()) navStack.removeAt(navStack.size - 1)
                wv.goBack()
            }
            wv != null && navStack.size > 1 -> {
                navStack.removeAt(navStack.size - 1)
                wv.loadUrl(navStack.last())
            }
            wv != null && !isHome(wv.url) -> wv.loadUrl(url)
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt < 2500) {
                    activity?.finishAndRemoveTask()
                } else {
                    lastBackAt = now
                    Toast.makeText(appContext, "Pulsa atrás otra vez para salir", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showDomains) {
        DomainsDialog(
            domains = domainsSnapshot,
            blockedCount = blockedCount,
            currentUrl = webView?.url,
            lastError = TvInput.lastError,
            context = appContext,
            onDismiss = {
                showDomains = false
                TvInput.dialogOpen = false
            }
        )
    }

    val hint: String? = when {
        !TvInput.enabled -> null
        TvInput.cursorMode -> "CURSOR  ▲▼◀▶ mover · OK clic · Atrás: salir del cursor"
        else -> "▲▼◀▶ mover · OK elegir · Atrás: volver"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(NightPurple)) {
            SlimTopBar(
                blockedCount = blockedCount,
                hint = hint,
                onShieldClick = {
                    domainsSnapshot = synchronized(AdBlocker.seenThirdParty) {
                        AdBlocker.seenThirdParty.toList()
                    }
                    TvInput.dialogOpen = true
                    showDomains = true
                }
            )

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        // ---------- BLINDAJE ----------
                        // Sin acceso a archivos del teléfono
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // Nada de contenido http dentro de páginas https
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // Navegación segura de Google (sitios de phishing / malware)
                        // En TV box se desactiva: puede demorar o frenar la carga del video.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            settings.safeBrowsingEnabled = !TvInput.enabled
                        }

                        // En TV, el navegador se identifica igual que en el celular (donde sí funciona),
                        // para que la página entregue el mismo reproductor.
                        if (TvInput.enabled) {
                            val ua = settings.userAgentString
                            if (!ua.contains("Mobile")) {
                                settings.userAgentString = ua.replace(" Safari/", " Mobile Safari/")
                            }
                        }
                        // Se identifica como Chrome normal (sin "; wv" ni "Version/x.y"): varios servidores
                        // de video y de la página dan error o anuncios a los WebView.
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv)", ")")
                            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
                        // Igual que Chrome: cookies de terceros activas (los reproductores y la página
                        // las usan para validar la sesión; sin ellas dan "404 - Not found").
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.setGeolocationEnabled(false)
                        WebView.setWebContentsDebuggingEnabled(false)

                        // --- Bloqueo de ventanas emergentes ---
                        settings.setSupportMultipleWindows(false)
                        settings.javaScriptCanOpenWindowsAutomatically = false

                        fun countBlocked() {
                            pendingBlocked.incrementAndGet()
                            if (flushScheduled.compareAndSet(false, true)) {
                                mainHandler.postDelayed({
                                    blockedCount += pendingBlocked.getAndSet(0)
                                    flushScheduled.set(false)
                                }, 1000)
                            }
                        }

                        // Bloquea cualquier descarga de archivos
                        setDownloadListener { _, _, _, _, _ -> countBlocked() }

                        // Inyecta el script en la página y en TODOS sus iframes (donde vive el reproductor)
                        val hasDocStart =
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        if (hasDocStart) TvInput.installScript(this)

                        webViewClient = object : WebViewClient() {

                            // Bloquea anuncios, rastreadores y todo lo que no sea https
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val u = request?.url ?: return null
                                if (u.scheme.equals("http", ignoreCase = true) && !AdBlocker.paused) {
                                    countBlocked()
                                    return AdBlocker.emptyResponse()
                                }
                                u.host?.let { h ->
                                    val b = AdBlocker.baseDomain(h)
                                    if (b != startBase) AdBlocker.seenThirdParty.add(b)
                                }
                                val hdr = request.requestHeaders
                                fun hv(k: String): String? =
                                    hdr?.entries?.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                                // Página principal: nada de scripts/imágenes de sitios no reconocidos
                                // (los reproductores, que son iframes con página HTML, no entran aquí)
                                if (!request.isForMainFrame &&
                                    hv("Accept")?.contains("text/html", ignoreCase = true) != true &&
                                    AdBlocker.isUnknownForTop(u.host, hv("Referer") ?: hv("Origin"))
                                ) {
                                    countBlocked()
                                    return AdBlocker.emptyResponse()
                                }
                                if (AdBlocker.isBlockedUrl(u.toString(), u.host, request.requestHeaders?.containsKey("Range") == true)) {
                                    countBlocked()
                                    return AdBlocker.emptyResponse()
                                }
                                return null
                            }

                            // Bloquea redirecciones a otros sitios y esquemas raros
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val req = request ?: return true
                                val u = req.url
                                val scheme = u.scheme?.lowercase()

                                // Solo https. intent://, market://, tel:, file:, http: -> bloqueado
                                if (scheme != "https" && !(AdBlocker.paused && scheme == "http")) {
                                    countBlocked()
                                    return true
                                }
                                // Antirrastreo: quita utm_*, fbclid, gclid... del enlace y carga la versión limpia
                                if (req.isForMainFrame) {
                                    val clean = AdBlocker.stripTracking(u)
                                    if (clean != null) {
                                        countBlocked()
                                        view?.loadUrl(clean.toString())
                                        return true
                                    }
                                }
                                if (AdBlocker.isBlockedUrl(u.toString(), u.host)) {
                                    countBlocked()
                                    return true
                                }
                                // Navegación principal a otro sitio: solo si es el mismo sitio, un servidor
                                // de video conocido o un salto seguido desde un servidor de video.
                                // Lo demás -> bloqueado (y se ofrece permitirlo con OK).
                                if (req.isForMainFrame && !AdBlocker.paused) {
                                    val target = u.host ?: ""
                                    val targetBase = AdBlocker.baseDomain(target)
                                    val curHost = Uri.parse(view?.url ?: "").host ?: ""
                                    val sameSite = targetBase == startBase ||
                                        (curHost.isNotEmpty() && targetBase == AdBlocker.baseDomain(curHost))
                                    if (!sameSite) {
                                        when {
                                            // Servidores de video conocidos o permitidos por la persona
                                            AdBlocker.isAllowedNav(target) -> AdBlocker.noteTrustedNav()
                                            // Saltos encadenados (redirección o script) desde un servidor de video
                                            AdBlocker.inTrustedChain() &&
                                                (req.isRedirect || AdBlocker.isAllowedNav(curHost)) ->
                                                AdBlocker.trust(target)
                                            else -> {
                                                countBlocked()
                                                AdBlocker.noteStrike(target)
                                                val blockedUrl = u.toString()
                                                mainHandler.post { TvInput.pendingNav = blockedUrl }
                                                return true
                                            }
                                        }
                                    }
                                }
                                return false
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request?.isForMainFrame == true) {
                                    val ru = request.url
                                    TvInput.lastError = "${errorResponse?.statusCode} · ${ru.host}${ru.path}"
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                                if (!isReload && visitedUrl != null && visitedUrl.startsWith("https://") &&
                                    navStack.lastOrNull() != visitedUrl
                                ) {
                                    navStack.add(visitedUrl)
                                    if (navStack.size > 40) navStack.removeAt(0)
                                }
                            }

                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, pageUrl, favicon)
                                // Página nueva: se vuelve a la navegación por botones
                                if (TvInput.enabled && !TvInput.fullscreen) TvInput.cursorMode = false
                                if (!hasDocStart && !AdBlocker.paused) view?.evaluateJavascript(AdBlocker.INJECTED_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                super.onPageFinished(view, pageUrl)
                                if (!hasDocStart && !AdBlocker.paused) view?.evaluateJavascript(AdBlocker.INJECTED_JS, null)
                                // Control remoto: resalta y permite elegir temporadas y capítulos
                                if (TvInput.enabled) {
                                    view?.evaluateJavascript(AdBlocker.TV_NAV_JS, null)
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            // Nunca abrir ventanas nuevas (popups / popunders)
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                countBlocked()
                                return false
                            }

                            // Bloquea alert/confirm de spam
                            override fun onJsAlert(
                                view: WebView?, url: String?, message: String?, result: JsResult?
                            ): Boolean {
                                result?.cancel()
                                return true
                            }

                            override fun onJsConfirm(
                                view: WebView?, url: String?, message: String?, result: JsResult?
                            ): Boolean {
                                result?.cancel()
                                return true
                            }

                            // Ninguna página puede pedir cámara, micrófono, ubicación ni archivos
                            override fun onPermissionRequest(request: PermissionRequest?) {
                                val allowed = request?.resources
                                    ?.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                                    .orEmpty()
                                if (allowed.isNotEmpty()) request?.grant(allowed.toTypedArray())
                                else request?.deny()
                            }

                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                callback?.invoke(origin, false, false)
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                return true
                            }

                            // --- Pantalla completa del video ---
                            override fun onShowCustomView(
                                view: View?,
                                callback: CustomViewCallback?
                            ) {
                                if (view == null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                customView = view
                                customCallback = callback
                                setFullscreen(true)
                            }

                            override fun onHideCustomView() {
                                customCallback = null
                                customView = null
                                setFullscreen(false)
                            }
                        }

                        loadUrl(url)
                        webView = this
                        TvInput.webView = this
                    }
                },
                onRelease = {
                    TvInput.webView = null
                    it.destroy()
                }
            )
        }

        // Video en pantalla completa por encima de todo
        customView?.let { v ->
            AndroidView(
                factory = { v },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }

        // Aviso de salto bloqueado
        val pending = TvInput.pendingNav
        if (pending != null) {
            LaunchedEffect(pending) {
                delay(9000)
                if (TvInput.pendingNav == pending) TvInput.pendingNav = null
            }
            NavPrompt(url = pending, onAllow = { TvInput.allowPendingNav(appContext) })
        }
    }
}

@Composable
private fun NavPrompt(url: String, onAllow: () -> Unit) {
    val host = remember(url) { Uri.parse(url).host ?: url }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = "Se bloqueó un salto a $host · " +
                if (TvInput.enabled) "pulsa OK para permitirlo" else "toca aquí para permitirlo",
            color = Color(0xFF1A1200),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(16.dp)
                .background(SimpsonYellow, RoundedCornerShape(12.dp))
                .clickable { onAllow() }
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    accent: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) accent else accent.copy(alpha = 0.14f))
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color.White else accent.copy(alpha = 0.55f),
                shape
            )
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (filled) Color(0xFF1A1200) else accent,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun StatCard(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF241A47), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(value, color = accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFB9B0D6), fontSize = 12.sp)
    }
}

@Composable
fun DomainsDialog(
    domains: List<String>,
    blockedCount: Int,
    currentUrl: String?,
    lastError: String?,
    context: Context,
    onDismiss: () -> Unit
) {
    // Fuerza recomposición al bloquear/permitir
    var tick by remember { mutableStateOf(0) }
    val paused = AdBlocker.paused
    val green = Color(0xFF4ADE80)
    val red = Color(0xFFFF6B6B)

    val rows = remember(tick, domains) {
        domains.map { it to AdBlocker.verdict(it) }
            .sortedWith(compareByDescending<Pair<String, Pair<Boolean, String>>> { it.second.first }.thenBy { it.first })
    }
    val blockedSites = rows.count { it.second.first }
    val allowedSites = rows.size - blockedSites

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 600.dp)
                .background(Color(0xFF1B1238), RoundedCornerShape(26.dp))
                .border(1.dp, Color(0x55FED90F), RoundedCornerShape(26.dp))
                .padding(20.dp)
        ) {
            // Encabezado
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0x33FED90F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = if (paused) Color(0xFFFFB84D) else SimpsonYellow,
                        modifier = Modifier.size(38.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Protección Simpson", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (paused) "En pausa · sin bloqueo" else "Activa · detector automático",
                        color = if (paused) Color(0xFFFFB84D) else green,
                        fontSize = 13.sp
                    )
                }
            }

            if (!currentUrl.isNullOrEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Página: " + currentUrl.removePrefix("https://").take(70),
                    color = Color(0xFFB9B0D6),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!lastError.isNullOrEmpty()) {
                Text(
                    "Último error: " + lastError.take(70),
                    color = Color(0xFFFFB84D),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCard("$blockedCount", "bloqueados", SimpsonYellow, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatCard("$blockedSites", "sitios bloqueados", red, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatCard("$allowedSites", "sitios permitidos", green, Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))
            PillButton(
                text = if (paused) "Reanudar protección" else "Pausar protección y recargar",
                accent = if (paused) green else SimpsonYellow,
                filled = true,
                modifier = Modifier.fillMaxWidth()
            ) {
                TvInput.setPaused(!AdBlocker.paused)
                onDismiss()
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "SITIOS DETECTADOS",
                color = Color(0xFFB9B0D6),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(6.dp))

            if (rows.isEmpty()) {
                Text(
                    "Todavía no se detectó ningún sitio externo. Espera a que cargue la página.",
                    color = Color(0xFFB9B0D6),
                    fontSize = 14.sp
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(rows) { (d, v) ->
                        val blocked = v.first
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp)
                                .background(Color(0xFF241A47), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (blocked) Icons.Filled.Block else Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = if (blocked) red else green,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    d,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    v.second,
                                    color = if (blocked) red else Color(0xFFB9B0D6),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            PillButton(
                                text = if (blocked) "Permitir" else "Bloquear",
                                accent = if (blocked) green else red,
                                filled = false
                            ) {
                                if (blocked) {
                                    AdBlocker.customBlocked.remove(d)
                                    AdBlocker.customAllowed.add(d)
                                    AdBlocker.learn(d, false)
                                } else {
                                    AdBlocker.customAllowed.remove(d)
                                    AdBlocker.customBlocked.add(d)
                                    AdBlocker.learn(d, true)
                                }
                                AdBlocker.saveCustom(context)
                                tick++
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                PillButton(text = "Cerrar", accent = Color.White, filled = false, onClick = onDismiss)
            }
        }
    }
}
