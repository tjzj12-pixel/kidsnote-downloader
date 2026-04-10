package com.kidsnote.downloader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loginView: View
    private lateinit var downloadView: View
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var childIdInput: EditText
    private lateinit var fromInput: EditText
    private lateinit var toInput: EditText
    private lateinit var folderInput: EditText
    private lateinit var btnAlbum: Button
    private lateinit var btnReport: Button
    private lateinit var btnBoth: Button
    private lateinit var btnStop: Button
    private lateinit var statusText: TextView

    private var cookies = ""
    private var downloadJob: Job? = null
    private var fileCount = 0
    private var errorCount = 0

    companion object {
        private const val BASE = "https://www.kidsnote.com/api/v1_2"
        private const val TZ = "Asia%2FSeoul"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginView    = findViewById(R.id.loginView)
        downloadView = findViewById(R.id.downloadView)
        webView      = findViewById(R.id.webView)
        logView      = findViewById(R.id.logView)
        scrollView   = findViewById(R.id.scrollView)
        childIdInput = findViewById(R.id.childIdInput)
        fromInput    = findViewById(R.id.fromInput)
        toInput      = findViewById(R.id.toInput)
        folderInput  = findViewById(R.id.folderInput)
        btnAlbum     = findViewById(R.id.btnAlbum)
        btnReport    = findViewById(R.id.btnReport)
        btnBoth      = findViewById(R.id.btnBoth)
        btnStop      = findViewById(R.id.btnStop)
        statusText   = findViewById(R.id.statusText)

        setupWebView()
        btnAlbum.setOnClickListener  { startDownload("album")  }
        btnReport.setOnClickListener { startDownload("report") }
        btnBoth.setOnClickListener   { startDownload("both")   }
        btnStop.setOnClickListener   { downloadJob?.cancel(); log("중지 요청됨") }
        findViewById<Button>(R.id.btnAutoDetect).setOnClickListener { autoDetectChildId() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val cookie = CookieManager.getInstance().getCookie("https://www.kidsnote.com") ?: return
                if (cookie.contains("sessionid") || cookie.contains("current_user")) {
                    cookies = cookie
                    runOnUiThread {
                        loginView.visibility = View.GONE
                        downloadView.visibility = View.VISIBLE
                        log("로그인 완료!")
                        autoDetectChildId()
                    }
                }
            }
        }
        webView.loadUrl("https://www.kidsnote.com/service/login")
    }

    private fun autoDetectChildId() {
        val js = "(function(){var ids=[];performance.getEntriesByType('resource').forEach(function(e){var m=e.name.match(/\\/children\\/(\\d+)\\//);if(m&&ids.indexOf(m[1])<0)ids.push(m[1]);});return ids.join(',');})()"
        webView.evaluateJavascript(js) { result ->
            val clean = result?.trim('"') ?: ""
            if (clean.isNotEmpty() && clean != "null") {
                runOnUiThread {
                    childIdInput.setText(clean)
                    log("아이 ID 자동 감지: $clean")
                }
            } else {
                runOnUiThread { log("아이 ID를 직접 입력해주세요") }
            }
        }
    }

    private fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        runOnUiThread {
            logView.append("$t $msg\n")
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateStatus() {
        runOnUiThread { statusText.text = "파일: ${fileCount}개  |  에러: ${errorCount}건" }
    }

    private fun setButtons(on: Boolean) {
        runOnUiThread {
            btnAlbum.isEnabled  = !on
            btnReport.isEnabled = !on
            btnBoth.isEnabled   = !on
            btnStop.isEnabled   =  on
        }
    }

    private fun startDownload(type: String) {
        val idText = childIdInput.text.toString().trim()
        if (idText.isEmpty()) { log("아이 ID를 입력하세요"); return }
        val ids    = idText.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val from   = fromInput.text.toString().trim()
        val to     = toInput.text.toString().trim()
        val folder = folderInput.text.toString().trim().ifEmpty { "Kidsnote" }

        fileCount = 0; errorCount = 0
        logView.text = ""
        setButtons(true)

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (id in ids) {
                    if (!isActive) break
                    log("아이 ID: $id")
                    if (type == "report" || type == "both") downloadReports(id, from, to, folder)
                    if (type == "album"  || type == "both") downloadAlbums(id, from, to, folder)
                }
                log("완료! 총 ${fileCount}개 저장, 에러 ${errorCount}건")
                log("저장위치: 내부저장소/Android/data/com.kidsnote.downloader/files/$folder")
            } catch (e: CancellationException) {
                log("중지됨")
            } catch (e: Exception) {
                log("오류: ${e.message}")
            } finally {
                setButtons(false)
            }
        }
    }

    private fun apiGet(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Cookie", cookies)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)")
            connectTimeout = 20000; readTimeout = 20000
        }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    private fun downloadReports(childId: String, from: String, to: String, root: String) {
        log("알림장 다운로드 시작...")
        for (month in getMonthRange(from, to)) {
            if (downloadJob?.isActive != true) break
            var url: String? = "$BASE/children/$childId/reports/?page_size=100&tz=$TZ&child=$childId&date_written=$month"
            var cnt = 0
            while (url != null && downloadJob?.isActive == true) {
                val data = apiGet(url)
                val results = data.optJSONArray("results") ?: break
                cnt += results.length()
                for (i in 0 until results.length()) {
                    if (downloadJob?.isActive != true) break
                    val rp   = results.getJSONObject(i)
                    val dw   = rp.optString("date_written", "$month-01")
                    val imgs = rp.optJSONArray("attached_images") ?: continue
                    val ym   = dw.take(7)
                    val day  = if (dw.length >= 10) dw.substring(8, 10) else "01"
                    for (j in 0 until imgs.length()) {
                        val imgUrl = imgs.getJSONObject(j).optString("original", "")
                        if (imgUrl.isEmpty()) continue
                        dlFile(imgUrl, "$root/알림장/$ym/$day", "${rp.optInt("id")}_${j+1}.jpg")
                    }
                }
                val next = data.optString("next")
                url = if (next.isEmpty() || next == "null") null else next
            }
            if (cnt > 0) log("  $month: ${cnt}건")
        }
    }

    private fun downloadAlbums(childId: String, from: String, to: String, root: String) {
        log("앨범 다운로드 시작...")
        var url: String? = "$BASE/children/$childId/albums/?page_size=100&child=$childId"
        val fromM = from.ifEmpty { "2010-01" }
        val toM   = to.ifEmpty   { "9999-12" }
        while (url != null && downloadJob?.isActive == true) {
            val data    = apiGet(url)
            val results = data.optJSONArray("results") ?: break
            for (i in 0 until results.length()) {
                if (downloadJob?.isActive != true) break
                val al   = results.getJSONObject(i)
                val date = al.optString("date", al.optString("created_at", ""))
                val ym   = if (date.length >= 7) date.take(7) else continue
                if (ym < fromM || ym > toM) continue
                val day  = if (date.length >= 10) date.substring(8, 10) else "01"
                val imgs = al.optJSONArray("attached_images") ?: al.optJSONArray("images") ?: continue
                val fp   = "$root/앨범/$ym/$day"
                log("  $fp (${imgs.length()}장)")
                for (j in 0 until imgs.length()) {
                    val imgUrl = imgs.getJSONObject(j).let {
                        it.optString("original", it.optString("image", ""))
                    }
                    if (imgUrl.isEmpty()) continue
                    dlFile(imgUrl, fp, "${al.optInt("id")}_${j+1}.jpg")
                }
            }
            val next = data.optString("next")
            url = if (next.isEmpty() || next == "null") null else next
        }
    }

    private fun dlFile(url: String, folder: String, filename: String) {
        try {
            val dir  = File(getExternalFilesDir(null), folder).also { it.mkdirs() }
            val file = File(dir, filename)
            if (file.exists() && file.length() > 0) return
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Referer", "https://www.kidsnote.com/")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 30000; readTimeout = 30000
            }
            file.writeBytes(conn.inputStream.readBytes())
            fileCount++
            log("  ✓ $filename")
        } catch (e: Exception) {
            errorCount++
            log("  ✗ $filename: ${e.message}")
        }
        Thread.sleep(200)
        updateStatus()
    }

    private fun getMonthRange(from: String, to: String): List<String> {
        val cal = Calendar.getInstance()
        val fy  = from.take(4).toIntOrNull() ?: 2015
        val fm  = if (from.length >= 7) from.substring(5,7).toIntOrNull() ?: 1 else 1
        val ty  = to.take(4).toIntOrNull() ?: cal.get(Calendar.YEAR)
        val tm  = if (to.length >= 7) to.substring(5,7).toIntOrNull() ?: (cal.get(Calendar.MONTH)+1) else cal.get(Calendar.MONTH)+1
        val months = mutableListOf<String>()
        var y = fy; var m = fm
        while (y < ty || (y == ty && m <= tm)) {
            months.add("$y-${m.toString().padStart(2,'0')}")
            if (++m > 12) { m = 1; y++ }
        }
        return months
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (loginView.visibility == View.VISIBLE && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
