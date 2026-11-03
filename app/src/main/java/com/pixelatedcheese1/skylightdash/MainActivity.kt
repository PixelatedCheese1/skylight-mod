package com.pixelatedcheese1.skylightdash

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

// ---- EDIT THESE ----
private const val CALENDAR_URL = "https://app.ourskylight.com"
private const val MAP_URL = "https://life360.com/login"
private const val WEATHER_URL = "https://wttr.in/"          // e.g. "https://wttr.in/Austin"
private const val WEATHER_REFRESH_MIN = 15L
// --------------------

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

class MainActivity : Activity() {
    private enum class Mode { DASH, CAL, MAP, WEATHER }

    private lateinit var calendar: WebView
    private lateinit var map: WebView
    private lateinit var weather: WebView
    private lateinit var rightCol: LinearLayout
    private val buttons = mutableMapOf<Mode, TextView>()
    private var mode = Mode.DASH
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            weather.reload()
            handler.postDelayed(this, WEATHER_REFRESH_MIN * 60_000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    private fun web(url: String, desktop: Boolean = false) = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        if (desktop) settings.userAgentString = DESKTOP_UA
        webViewClient = WebViewClient()
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        loadUrl(url)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        CookieManager.getInstance().setAcceptCookie(true)

        calendar = web(CALENDAR_URL)
        map = web(MAP_URL, desktop = true)
        weather = web(WEATHER_URL)

        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }

        // Sidebar
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1B1F27"))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        listOf(
            Mode.DASH to "▦\nAll", Mode.CAL to "📅\nCalendar",
            Mode.MAP to "📍\nMap", Mode.WEATHER to "☀\nWeather"
        ).forEach { (m, label) ->
            val b = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; textSize = 13f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).also { it.setMargins(dp(6), dp(6), dp(6), dp(6)) }
                setOnClickListener { setMode(m) }
            }
            buttons[m] = b; side.addView(b)
        }

        // Content: calendar left, map + weather stacked right
        rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        rightCol.addView(map.apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).also { it.setMargins(dp(3), dp(3), dp(3), dp(3)) } })
        rightCol.addView(weather.apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).also { it.setMargins(dp(3), dp(3), dp(3), dp(3)) } })
        calendar.layoutParams = LinearLayout.LayoutParams(0, -1, 1.15f).also { it.setMargins(dp(3), dp(3), dp(3), dp(3)) }

        root.addView(side); root.addView(calendar); root.addView(rightCol)
        setContentView(root)
        setMode(Mode.DASH)
    }

    private fun setMode(m: Mode) {
        mode = m
        calendar.visibility = if (m == Mode.DASH || m == Mode.CAL) View.VISIBLE else View.GONE
        rightCol.visibility = if (m == Mode.CAL) View.GONE else View.VISIBLE
        map.visibility = if (m == Mode.DASH || m == Mode.MAP) View.VISIBLE else View.GONE
        weather.visibility = if (m == Mode.DASH || m == Mode.WEATHER) View.VISIBLE else View.GONE
        buttons.forEach { (k, b) ->
            b.setBackgroundColor(if (k == m) Color.parseColor("#3D7BFF") else Color.parseColor("#2A303B"))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onResume() {
        super.onResume()
        listOf(calendar, map, weather).forEach { it.onResume() }
        handler.postDelayed(refresh, WEATHER_REFRESH_MIN * 60_000)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        listOf(calendar, map, weather).forEach { it.onPause() }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val focused = when (mode) { Mode.CAL -> calendar; Mode.MAP -> map; Mode.WEATHER -> weather; else -> calendar }
        if (focused.canGoBack()) focused.goBack()
    }
}
