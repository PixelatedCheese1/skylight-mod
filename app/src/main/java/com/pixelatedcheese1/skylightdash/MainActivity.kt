package com.pixelatedcheese1.skylightdash

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

// ---- EDIT THESE (city can also be changed on the device by tapping the weather card) ----
private const val CALENDAR_URL = "https://app.ourskylight.com"
private const val MAP_URL = "https://life360.com/login"
private const val DEFAULT_CITY = "New York"
private const val FAHRENHEIT = true
private const val REFRESH_MIN = 15L
// -----------------------------------------------------------------------------------------

private val BG = Color.parseColor("#0E1116")
private val CARD = Color.parseColor("#171B22")
private val MUTED = Color.parseColor("#8B93A1")
private val ACCENT = Color.parseColor("#2F6BFF")

class MainActivity : Activity() {
    private enum class Mode { DASH, CAL, MAP, WEATHER }

    private lateinit var calCard: View
    private lateinit var mapCard: View
    private lateinit var wxCard: View
    private lateinit var rightCol: LinearLayout
    private lateinit var wxBox: LinearLayout
    private lateinit var calWeb: WebView
    private lateinit var mapWeb: WebView
    private lateinit var chromeUa: String
    private lateinit var desktopUa: String
    private val nav = mutableMapOf<Mode, LinearLayout>()
    private var mode = Mode.DASH
    private var wx: JSONObject? = null
    private var wxPlace = ""
    private var wxNote = "Loading weather…"
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { fetchWeather(); handler.postDelayed(this, REFRESH_MIN * 60_000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("dash", MODE_PRIVATE)
    private fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE
    private fun lp(w: Int, h: Int, wt: Float = 0f) =
        LinearLayout.LayoutParams(w, h, wt).also { it.setMargins(dp(6), dp(6), dp(6), dp(6)) }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(content: View) = FrameLayout(this).apply {
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(20).toFloat() }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        addView(content, FrameLayout.LayoutParams(-1, -1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webCard(url: String, desktop: Boolean): Pair<FrameLayout, WebView> {
        val wv = WebView(this)
        wv.setBackgroundColor(CARD)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = if (desktop) desktopUa else chromeUa
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        val frame = card(wv)
        val msg = TextView(this).apply {
            visibility = View.GONE; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(Color.WHITE); setBackgroundColor(CARD); setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener { visibility = View.GONE; wv.reload() }
        }
        frame.addView(msg, FrameLayout.LayoutParams(-1, -1))
        fun fail(t: String) = runOnUiThread { msg.text = t; msg.visibility = View.VISIBLE }

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) { msg.visibility = View.GONE }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(v: WebView?, code: Int, desc: String?, failingUrl: String?) {
                fail("Couldn't load this page\n\n$desc\n\nTap to retry")
            }

            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) {
                h?.cancel()
                fail("Secure connection failed (SSL error ${e?.primaryError}).\nCheck the device date/time or update the system WebView.\n\nTap to retry")
            }
        }
        wv.loadUrl(url)
        return frame to wv
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)
        val def = WebSettings.getDefaultUserAgent(this)
        chromeUa = def.replace("; wv", "").replace(Regex(" Version/[\\d.]+"), "")
        val ver = Regex("Chrome/([\\d.]+)").find(def)?.groupValues?.get(1) ?: "120.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)

        val (cc, cw) = webCard(CALENDAR_URL, false); calCard = cc; calWeb = cw
        val (mc, mw) = webCard(MAP_URL, true); mapCard = mc; mapWeb = mw
        wxBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        wxCard = card(wxBox).apply { setOnClickListener { askCity() } }

        // Sidebar: clock + nav rail
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(12))
        }
        side.addView(TextClock(this).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 26f
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setOnLongClickListener { showEngine(); true }
        })
        side.addView(TextClock(this).apply {
            format12Hour = "EEE, MMM d"; format24Hour = "EEE, MMM d"; textSize = 11f; setTextColor(MUTED)
        })
        side.addView(View(this), LinearLayout.LayoutParams(-1, dp(24)))
        listOf(
            Triple(Mode.DASH, "▦", "All"), Triple(Mode.CAL, "📅", "Calendar"),
            Triple(Mode.MAP, "📍", "Map"), Triple(Mode.WEATHER, "☀️", "Weather")
        ).forEach { (m, icon, name) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(label(icon, 22f, Color.WHITE)); addView(label(name, 11f, Color.WHITE))
                layoutParams = LinearLayout.LayoutParams(-1, dp(72)).also { it.setMargins(dp(8), dp(4), dp(8), dp(4)) }
                setOnClickListener { setMode(m) }
                setOnLongClickListener { reload(m); true }
            }
            nav[m] = item; side.addView(item)
        }
        side.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol.addView(mapCard, lp(-1, 0, 1.15f))
        rightCol.addView(wxCard, lp(-1, 0, 1f))
        val content = LinearLayout(this).apply { setPadding(dp(2), dp(2), dp(2), dp(2)) }
        content.addView(calCard, lp(0, -1, 1.25f))
        content.addView(rightCol, LinearLayout.LayoutParams(0, -1, 1f))

        val root = LinearLayout(this).apply { setBackgroundColor(BG) }
        root.addView(side, LinearLayout.LayoutParams(dp(92), -1))
        root.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        setMode(Mode.DASH)
        fetchWeather()
    }

    private fun setMode(m: Mode) {
        mode = m
        calCard.visibility = vis(m == Mode.DASH || m == Mode.CAL)
        rightCol.visibility = vis(m != Mode.CAL)
        mapCard.visibility = vis(m == Mode.DASH || m == Mode.MAP)
        wxCard.visibility = vis(m == Mode.DASH || m == Mode.WEATHER)
        nav.forEach { (k, v) ->
            v.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(if (k == m) ACCENT else Color.TRANSPARENT)
            }
        }
        renderWeather()
    }

    private fun reload(m: Mode) {
        when (m) {
            Mode.DASH -> { calWeb.reload(); mapWeb.reload(); fetchWeather() }
            Mode.CAL -> calWeb.reload()
            Mode.MAP -> mapWeb.reload()
            Mode.WEATHER -> fetchWeather()
        }
    }

    private fun showEngine() {
        val eng = if (Build.VERSION.SDK_INT >= 26)
            WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
        else "unknown (needs Android 8+)"
        Toast.makeText(this, "WebView: $eng\nAndroid ${Build.VERSION.RELEASE}", Toast.LENGTH_LONG).show()
    }

    // ---------- Weather (Open-Meteo, no key, no website) ----------
    private fun askCity() {
        val et = EditText(this).apply { setText(prefs().getString("city", DEFAULT_CITY)); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Weather city").setView(et)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("city", et.text.toString().trim()).apply(); fetchWeather()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun fetchWeather() {
        val city = prefs().getString("city", DEFAULT_CITY) ?: DEFAULT_CITY
        thread {
            try {
                val geo = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8")))
                val r = geo.optJSONArray("results")?.optJSONObject(0) ?: throw Exception("City not found: $city")
                val lat = r.getDouble("latitude"); val lon = r.getDouble("longitude")
                val units = if (FAHRENHEIT) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else ""
                val w = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=5&timezone=auto$units"))
                runOnUiThread { wx = w; wxPlace = r.optString("name", city); renderWeather() }
            } catch (e: Exception) {
                runOnUiThread { wx = null; wxNote = "Weather unavailable\n${e.message}\n\nTap to set city"; renderWeather() }
            }
        }
    }

    private fun wxInfo(c: Int, night: Boolean): Pair<String, String> = when (c) {
        0 -> (if (night) "🌙" else "☀️") to "Clear"
        1 -> (if (night) "🌙" else "🌤️") to "Mostly clear"
        2 -> "⛅" to "Partly cloudy"
        3 -> "☁️" to "Overcast"
        45, 48 -> "🌫️" to "Fog"
        in 51..57 -> "🌦️" to "Drizzle"
        in 61..67, in 80..82 -> "🌧️" to "Rain"
        in 71..77, 85, 86 -> "🌨️" to "Snow"
        in 95..99 -> "⛈️" to "Thunderstorm"
        else -> "🌡️" to "—"
    }

    private fun renderWeather() {
        wxBox.removeAllViews()
        val k = if (mode == Mode.WEATHER) 1.5f else 1f
        val w = wx
        if (w == null) { wxBox.addView(label(wxNote, 16f * k, MUTED)); return }
        val cur = w.getJSONObject("current")
        val (icon, text) = wxInfo(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 0)
        val speed = if (FAHRENHEIT) "mph" else "km/h"

        wxBox.addView(label(wxPlace, 14f * k, MUTED))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(icon, 44f * k, Color.WHITE))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        col.addView(label("${Math.round(cur.getDouble("temperature_2m"))}°", 44f * k, Color.WHITE, true))
        col.addView(label(text, 15f * k, MUTED))
        top.addView(col)
        wxBox.addView(top)
        wxBox.addView(label("Feels ${Math.round(cur.getDouble("apparent_temperature"))}°  ·  " +
            "${Math.round(cur.getDouble("relative_humidity_2m"))}% humidity  ·  " +
            "${Math.round(cur.getDouble("wind_speed_10m"))} $speed", 13f * k, MUTED))

        val d = w.getJSONObject("daily")
        val days = d.getJSONArray("time")
        val row = LinearLayout(this).apply { setPadding(0, dp(16), 0, 0) }
        val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE", Locale.getDefault())
        for (i in 0 until days.length()) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val name = if (i == 0) "Today" else outFmt.format(inFmt.parse(days.getString(i))!!)
            cell.addView(label(name, 12f * k, MUTED))
            cell.addView(label(wxInfo(d.getJSONArray("weather_code").getInt(i), false).first, 24f * k, Color.WHITE))
            cell.addView(label("${Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))}°", 15f * k, Color.WHITE, true))
            cell.addView(label("${Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))}°", 13f * k, MUTED))
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        wxBox.addView(row)
    }

    // ---------- Lifecycle ----------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onResume() {
        super.onResume()
        calWeb.onResume(); mapWeb.onResume()
        handler.postDelayed(refresh, REFRESH_MIN * 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        calWeb.onPause(); mapWeb.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = if (mode == Mode.MAP) mapWeb else calWeb
        if (w.canGoBack()) w.goBack()
    }
}
