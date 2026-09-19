package dev.openfit.phone.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import dev.openfit.phone.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders one of the user's own Dreeve pages inside the app.
 *
 * The page keeps its own markup/JS (so every stat, chart and map behaves exactly as on the server);
 * we only (a) set Dreeve's theme attribute to follow the app, (b) hide Dreeve's own top bar/sidebar,
 * which our navigation replaces, and (c) keep in-app navigation inside this WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(
    baseUrl: String,
    path: String,
    title: String,
    dark: Boolean,
    onMenu: () -> Unit,
    onExit: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("$baseUrl$path") }
    var currentUrl by remember { mutableStateOf("$baseUrl$path") }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Match a mobile browser's viewport: without this the page sees a bogus height and
            // Dreeve's vh-based layout maths (max-h-[calc(100vh-…)] on list pages) collapse.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    android.util.Log.d(
                        "openFitWeb",
                        "[${m.messageLevel()}] ${m.message()} @ ${m.sourceId()}:${m.lineNumber()}",
                    )
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    return if (target.startsWith(baseUrl) || target.startsWith("about:")) {
                        false // keep the user on their own server, inside the app
                    } else {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                        true
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    error = null
                    if (url != null) currentUrl = url
                }

                /** Dreeve navigates client-side, so the URL can change without a page load. */
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (url != null) currentUrl = url
                    canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                    if (url != null) currentUrl = url
                    view.evaluateJavascript(injection(dark, BuildConfig.DEBUG), null)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    err: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        loading = false
                        error = err.description?.toString() ?: "Could not load the page"
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        webView.loadUrl(url)
        onDispose { runCatching { webView.destroy() } }
    }

    BackHandler(enabled = true) {
        if (canGoBack) webView.goBack() else onExit()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    if (activityIdFrom(currentUrl) != null) "Activity" else title,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onMenu) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                val activityId = activityIdFrom(currentUrl)
                if (activityId != null) {
                    TextButton(onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { saveGpx(ctx, baseUrl, activityId) }
                            Toast.makeText(
                                ctx,
                                if (ok) "GPX saved to Downloads" else "Could not download the GPX",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) { Text("GPX") }
                }
                TextButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: url))) }
                }) { Text("Browser") }
                IconButton(onClick = { error = null; webView.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        val err = error
        if (err != null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Could not load $title", style = MaterialTheme.typography.titleMedium)
                Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { error = null; webView.reload() }) { Text("Retry") }
                    TextButton(onClick = { url = "$baseUrl$path"; webView.loadUrl(url) }) { Text("Reload page") }
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { webView },
            )
        }
    }
}

/** Dreeve theme + hide its own chrome (our top bar and drawer replace it). */
private fun injection(dark: Boolean, debug: Boolean): String {
    val theme = if (dark) "dark" else "light"
    val css = "div.antialiased>nav,aside#drawer-navigation{display:none!important}" +
        "nav[aria-label='Breadcrumb']{display:none!important}" +
        "main{padding-top:0.5rem!important}" +
        "body{padding-bottom:28px!important}" +
        // Dreeve's data-table wrapper uses max-h-[calc(100vh-...)], which the WebView resolves
        // to 0px and collapses every list to a thin strip. Let the page scroll vertically, but
        // keep horizontal scrolling so wide tables stay reachable.
        ".scroll-area{max-height:none!important;height:auto!important;overflow-x:auto!important;overflow-y:visible!important}" +
        "body{padding-bottom:72px!important}"
    val probe = if (!debug) "" else """
          if (!window.__openfitNet) {
            window.__openfitNet = true;
            var of = window.fetch;
            window.fetch = function(){
              var a = arguments[0];
              var u = (typeof a === 'string') ? a : ((a && a.url) || '');
              return of.apply(this, arguments).then(
                function(r){ console.log('OF-FETCH ' + r.status + ' ' + u); return r; },
                function(e){ console.log('OF-FETCH-ERR ' + u + ' :: ' + e); throw e; });
            };
            var oo = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(m, u){
              this.addEventListener('loadend', function(){ console.log('OF-XHR ' + this.status + ' ' + u); });
              return oo.apply(this, arguments);
            };
            document.addEventListener('error', function(ev){
              try { console.log('OF-JSERR ' + (ev.message || '') + ' @ ' + (ev.filename || '') + ':' + (ev.lineno || 0)); } catch (e) {}
            });
            function ofProbe(tag){
              try {
                var c = document.querySelector('[data-dataTable-settings]');
                var sa = document.querySelector('.scroll-area');
                var tb = sa ? sa.querySelector('table') : null;
                console.log('OF-PROBE ' + tag +
                  ' vh=' + window.innerHeight +
                  ' docCH=' + document.documentElement.clientHeight +
                  ' saH=' + (sa ? Math.round(sa.getBoundingClientRect().height) : -1) +
                  ' saMax=' + (sa ? getComputedStyle(sa).maxHeight : '-') +
                  ' tableH=' + (tb ? Math.round(tb.getBoundingClientRect().height) : -1) +
                  ' tbodyRows=' + (sa ? sa.querySelectorAll('tbody tr').length : -1) +
                  ' boxH=' + (c ? Math.round(c.getBoundingClientRect().height) : -1) +
                  ' bodyH=' + document.body.scrollHeight);
              } catch (e) { console.log('OF-PROBE-ERR ' + e); }
            }
            setTimeout(function(){ ofProbe('t3'); }, 3000);
            setTimeout(function(){ ofProbe('t9'); }, 9000);
            document.addEventListener('click', function(e){
              try {
                var a = e.target && e.target.closest ? e.target.closest('a') : null;
                console.log('OF-CLICK x=' + Math.round(e.clientX) + ' y=' + Math.round(e.clientY) +
                  ' target=' + (e.target && e.target.tagName) +
                  ' link=' + (a ? a.getAttribute('href') : 'none'));
              } catch (err) {}
            }, true);
          }
    """
    // Dreeve's own JS replaces DOM nodes as you browse, so re-assert the style element
    // (MutationObserver + a slow interval) instead of applying it once.
    return """
        (function(){
          try { document.documentElement.setAttribute('data-theme','$theme'); } catch (e) {}
          var css = ${"\"$css\""};
          function ensure(){
            var el = document.getElementById('openfit-chrome');
            if (!el) {
              el = document.createElement('style');
              el.id = 'openfit-chrome';
              (document.head || document.documentElement).appendChild(el);
            }
            if (el.textContent !== css) { el.textContent = css; }
          }
          ensure();
          try { new MutationObserver(ensure).observe(document.documentElement, {childList:true, subtree:true}); } catch (e) {}
          if (!window.__openfitTimer) { window.__openfitTimer = setInterval(ensure, 1500); }
          try { $probe } catch (e) {}
        })();
    """.trimIndent()
}

/** Extract the Dreeve activity id when the user is looking at an activity page. */
fun activityIdFrom(url: String?): String? {
    if (url == null) return null
    val m = Regex("""/activities/([A-Za-z0-9-]+)""").find(url) ?: return null
    return m.groupValues[1]
}

/** Download an activity's GPX route from the user's server into Downloads. */
fun saveGpx(ctx: Context, baseUrl: String, activityId: String): Boolean {
    return try {
        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url("$baseUrl/api/internal/activity/$activityId/route.gpx")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val bytes = resp.body?.bytes() ?: return false
            val name = "openfit-$activityId.gpx"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            } else {
                val dir = File(ctx.getExternalFilesDir(null), "gpx").apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
            }
            true
        }
    } catch (e: Exception) {
        false
    }
}
