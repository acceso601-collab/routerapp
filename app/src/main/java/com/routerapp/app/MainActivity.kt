package com.routerapp.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnAutofill: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences("routerapp", Context.MODE_PRIVATE) }
    private val ROUTER_URL = "http://10.0.0.1"

    // Credenciales guardadas
    private var savedUser: String get() = prefs.getString("user", "") ?: ""
        set(v) { prefs.edit().putString("user", v).apply() }
    private var savedPass: String get() = prefs.getString("pass", "") ?: ""
        set(v) { prefs.edit().putString("pass", v).apply() }
    private var hasCredentials: Boolean get() = prefs.getBoolean("has_creds", false)
        set(v) { prefs.edit().putBoolean("has_creds", v).apply() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView      = findViewById(R.id.webView)
        btnAutofill  = findViewById(R.id.btnAutofill)
        progressBar  = findViewById(R.id.progressBar)

        setupWebView()

        // Botón autofill flotante
        btnAutofill.setOnClickListener {
            if (hasCredentials) {
                injectCredentials()
            } else {
                showSaveCredentialsDialog()
            }
        }

        // Mantener presionado para editar credenciales
        btnAutofill.setOnLongClickListener {
            showSaveCredentialsDialog()
            true
        }

        webView.loadUrl(ROUTER_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        }

        // Aceptar todos los certificados SSL del router
        webView.webViewClient = object : WebViewClient() {

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {
                handler.proceed() // Aceptar cert auto-firmado del router
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE

                // Auto-rellenar si hay credenciales guardadas
                if (hasCredentials) {
                    injectCredentials()
                }

                // Detectar si hay campos de login para ofrecer guardar
                detectLoginForm(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showConnectionError()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }

        // Interfaz JS para detectar envío de credenciales
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onLoginDetected(user: String, pass: String) {
                if (user.isNotEmpty() && pass.isNotEmpty() && !hasCredentials) {
                    runOnUiThread {
                        offerSaveCredentials(user, pass)
                    }
                }
            }
        }, "RouterBridge")
    }

    // ── INYECCIÓN DE CREDENCIALES ─────────────────────────────────────────────

    private fun injectCredentials() {
        val user = savedUser.replace("'", "\\'")
        val pass = savedPass.replace("'", "\\'")

        val js = """
        (function() {
            // Selectores comunes para routers
            var userSelectors = [
                'input[type=text]', 'input[name*=user]', 'input[id*=user]',
                'input[name*=login]', 'input[id*=login]', 'input[name*=name]',
                'input[placeholder*=user]', 'input[placeholder*=User]',
                'input[type=email]', '#username', '#user', '#login'
            ];
            var passSelectors = [
                'input[type=password]', 'input[name*=pass]', 'input[id*=pass]',
                '#password', '#pass', '#pwd'
            ];

            function fillField(selectors, value) {
                for (var i = 0; i < selectors.length; i++) {
                    var el = document.querySelector(selectors[i]);
                    if (el && el.offsetParent !== null) {
                        el.value = value;
                        el.dispatchEvent(new Event('input', {bubbles: true}));
                        el.dispatchEvent(new Event('change', {bubbles: true}));
                        return true;
                    }
                }
                return false;
            }

            var userFilled = fillField(userSelectors, '$user');
            var passFilled = fillField(passSelectors, '$pass');

            if (userFilled || passFilled) {
                console.log('[RouterApp] Credenciales rellenadas');
            }
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun detectLoginForm(view: WebView) {
        val js = """
        (function() {
            var forms = document.querySelectorAll('form');
            forms.forEach(function(form) {
                form.addEventListener('submit', function() {
                    var passField = form.querySelector('input[type=password]');
                    var userField = form.querySelector('input[type=text], input[type=email], input[name*=user], input[id*=user]');
                    if (passField && userField) {
                        RouterBridge.onLoginDetected(userField.value, passField.value);
                    }
                }, true);
            });
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ── DIÁLOGOS ──────────────────────────────────────────────────────────────

    private fun offerSaveCredentials(user: String, pass: String) {
        AlertDialog.Builder(this)
            .setTitle("💾 Guardar credenciales")
            .setMessage("¿Quieres guardar usuario y contraseña para acceder con un toque la próxima vez?")
            .setPositiveButton("Guardar") { _, _ ->
                savedUser = user
                savedPass = pass
                hasCredentials = true
                updateFabIcon()
                Toast.makeText(this, "✅ Credenciales guardadas", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("No, gracias", null)
            .show()
    }

    private fun showSaveCredentialsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_credentials, null)
        val etUser = view.findViewById<EditText>(R.id.etUser)
        val etPass = view.findViewById<EditText>(R.id.etPass)

        etUser.setText(savedUser)
        etPass.setText(savedPass)

        AlertDialog.Builder(this)
            .setTitle(if (hasCredentials) "✏️ Editar credenciales" else "🔑 Guardar credenciales")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    savedUser = user
                    savedPass = pass
                    hasCredentials = true
                    updateFabIcon()
                    injectCredentials()
                    Toast.makeText(this, "✅ Guardado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Completa los campos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Borrar") { _, _ ->
                savedUser = ""
                savedPass = ""
                hasCredentials = false
                updateFabIcon()
                Toast.makeText(this, "Credenciales eliminadas", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showConnectionError() {
        webView.loadDataWithBaseURL(null, """
            <html><body style="background:#0d1117;color:#e2e8f0;font-family:sans-serif;
            display:flex;flex-direction:column;align-items:center;justify-content:center;
            height:100vh;margin:0;text-align:center;padding:20px;">
            <div style="font-size:48px">📡</div>
            <h2 style="color:#00ffc3">Sin conexión al router</h2>
            <p style="color:#4a5568">Asegúrate de estar conectado al WiFi de tu red local</p>
            <p style="color:#4a5568">Gateway: <b style="color:#00ffc3">10.0.0.1</b></p>
            <button onclick="location.reload()" style="margin-top:20px;padding:12px 24px;
            background:#00ffc3;border:none;border-radius:8px;font-size:16px;cursor:pointer">
            🔄 Reintentar</button>
            </body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }

    private fun updateFabIcon() {
        btnAutofill.setImageResource(
            if (hasCredentials) android.R.drawable.ic_input_get
            else android.R.drawable.ic_lock_lock
        )
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
