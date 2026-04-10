package com.kidsnote.downloader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONObject
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
    private lateinit var folderPathText: TextView
    private lateinit var btnAlbum: Button
    private lateinit var btnReport: Button
    private lateinit var btnBoth: Button
    private lateinit var btnStop: Button
    private lateinit var statusText: TextView

    private var cookies = ""
    private var downloadJob: Job? = null
    private var fileCount = 0
    private var errorCount = 0
    private var selectedFolderUri: Uri? = null

    companion object {
        private const val BASE        = "https://www.kidsnote.com/api/v1_2"
        private const val TZ          = "Asia%2FSeoul"
        private const val PREFS       = "kidsnote_prefs"
        private const val PREF_FOLDER = "folder_uri"
        private const val PREF_FIRST  = "first_launch"
        private const val REQ_FOLDER  = 1001
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        loginView      = findViewById(R.id.loginView)
        downloadView   = findViewById(R.id.downloadView)
        webView        = findViewById(R.id.webView)
        logView        = findViewById(R.id.logView)
        scrollView     = findViewById(R.id.scrollView)
        childIdInput   = findViewById(R.id.childIdInput)
        fromInput      = findViewById(R.id.fromInput)
        toInput        = findViewById(R.id.toInput)
        folderPathText = findViewById(R.id.folderPathText)
        btnAlbum       = findViewById(R.id.btnAlbum)
        btnReport      = findViewById(R.id.btnReport)
        btnBoth        = findViewById(R.id.btnBoth)
        btnStop        = findViewById(R.id.btnStop)
        statusText     = findViewById(R.id.statusText)

        // 저장된 폴더 URI 복원
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(PREF_FOLDER, null)?.let {
            selectedFolderUri = Uri.parse(it)
            updateFolderDisplay(Uri.parse(it))
        }

        setupWebView()

        // 첫 실행 시 가이드 표시
        if (prefs.getBoolean(PREF_FIRST, true)) {
            prefs.edit().putBoolean(PREF_FIRST, false).apply()
            showGuide()
        }

        findViewById<Button>(R.id.btnGuide).setOnClickListener      { showGuide() }
        findViewById<Button>(R.id.btnGuideLogin).setOnClickListener  { showGuide() }
        findViewById<Button>(R.id.btnFolderPick).setOnClickListener  { pickFolder() }
        findViewById<Button>(R.id.btnAutoDetect).setOnClickListener  { autoDetectChildId() }
        btnAlbum.setOnClickListener  { startDownload("album")  }
        btnReport.setOnClickListener { startDownload("report") }
        btnBoth.setOnClickListener   { startDownload("both")   }
        btnStop.setOnClickListener   { downloadJob?.cancel(); log("중지 요청됨") }
    }

    // ── 폴더 선택 (SAF) ───────────────────────────────────────────────────────
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_FOLDER)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedFolderUri = uri
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_FOLDER, uri.toString()).apply()
            updateFolderDisplay(uri)
        }
    }

    private fun updateFolderDisplay(uri: Uri) {
        val raw = uri.lastPathSegment ?: uri.toString()
        folderPathText.text = raw.replace("primary:", "내부저장소/")
    }

    // ── 가이드 다이얼로그 ─────────────────────────────────────────────────────
    private fun showGuide() {
        val sep = "--------------------------------"
        val lines = listOf(
            "   키즈노트 다운로더 사용 가이드",
            "",
            "[STEP 1] 아이 ID 확인 (PC 필요)",
            sep,
            "1. PC 크롬 > www.kidsnote.com 로그인",
            "2. 알림장 페이지로 이동",
            "3. F12 키 > Network 탭 클릭",
            "4. 알림장 게시물 아무거나 클릭",
            "5. 요청 목록에서 reports 포함",
            "   항목 클릭",
            "6. Headers 탭 > :path 에서 확인:",
            "   /children/[숫자]/reports/",
            "   => 이 숫자가 아이 ID 입니다",
            "",
            "  아이 2명이면 각각 알림장을",
            "  전환하며 ID 확인 후 쉼표로 입력",
            "  예) 4985393, 5123456",
            "",
            "[STEP 2] 앱에서 로그인",
            sep,
            "앱 실행 후 보이는 화면에서",
            "키즈노트 아이디/비밀번호 입력",
            "",
            "[STEP 3] 저장 폴더 선택",
            sep,
            "[선택] 버튼 탭하면 폴더 선택창이",
            "열립니다. 원하는 폴더를 선택하세요.",
            "예) 내부저장소 > DCIM > Kidsnote",
            "",
            "* 반드시 다운로드 전에 선택 필요",
            "* 선택한 폴더는 저장됩니다",
            "",
            "[STEP 4] 기간 설정 및 다운로드",
            sep,
            "- 기간 빈칸 = 전체 (오래 걸림)",
            "- 특정 기간: YYYY-MM 형식 입력",
            "  from: 2022-01  to: 2023-12",
            "- [전체 다운로드] = 앨범+알림장",
            "",
            "[다운로드 후 파일 위치]",
            sep,
            "선택한 폴더 안에 자동 정리됩니다:",
            "",
            "선택폴더/",
            "  앨범/",
            "    2023-01/15/ ...",
            "  알림장/",
            "    2023-01/15/ ...",
            "",
            "갤러리 앱에서도 자동으로 보입니다."
        )

        AlertDialog.Builder(this)
            .setTitle("사용 가이드")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("확인") { d, _ -> d.dismiss() }
            .show()
            .apply {
                findViewById<TextView>(android.R.id.message)?.apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = 12f
                    setLineSpacing(6f, 1f)
                    setPadding(48, 24, 48, 24)
                }
            }
    }

    // ── WebView 로그인 ────────────────────────────────────────────────────────
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
                val cookie = CookieManager.getInstance()
                    .getCookie("https://www.kidsnote.com") ?: return
                if (cookie.contains("sessionid") || cookie.contains("current_user")) {
                    cookies = cookie
                    runOnUiThread {
                        loginView.visibility    = View.GONE
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
        val js = "(function(){var ids=[];performance.getEntriesByType('resource')" +
            ".forEach(function(e){var m=e.name.match(/\\/children\\/(\\d+)\\//)" +
            ";if(m&&ids.indexOf(m[1])<0)ids.push(m[1]);});return ids.join(',');})()"
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

    // ── UI 헬퍼 ──────────────────────────────────────────────────────────────
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

    // ── 다운로드 시작 ─────────────────────────────────────────────────────────
    private fun startDownload(type: String) {
        if (selectedFolderUri == null) {
            AlertDialog.Builder(this)
                .setTitle("저장 폴더 미선택")
                .setMessage("다운로드 전에 저장 폴더를 선택해주세요.\n[선택] 버튼을 탭하세요.")
                .setPositiveButton("폴더 선택") { _, _ -> pickFolder() }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        val idText = childIdInput.text.toString().trim()
        if (idText.isEmpty()) { log("아이 ID를 입력하세요"); return }

        val ids  = idText.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        val from = fromInput.text.toString().trim()
        val to   = toInput.text.toString().trim()

        fileCount = 0; errorCount = 0
        logView.text = ""
        setButtons(true)

        downloadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (id in ids) {
                    if (!isActive) break
                    log("\n아이 ID: $id")
                    if (type == "report" || type == "both") downloadReports(id, from, to)
                    if (type == "album"  || type == "both") downloadAlbums(id, from, to)
                }
                log("\n완료! 총 ${fileCount}개 저장, 에러 ${errorCount}건")
            } catch (e: CancellationException) {
                log("중지됨")
            } catch (e: Exception) {
                log("오류: ${e.message}")
            } finally {
                setButtons(false)
            }
        }
    }

    // ── API 요청 ──────────────────────────────────────────────────────────────
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

    // ── 알림장 다운로드 ───────────────────────────────────────────────────────
    private fun downloadReports(childId: String, from: String, to: String) {
        log("알림장 다운로드...")
        val fromM = from.ifEmpty { "2010-01" }
        val toM   = to.ifEmpty   { "9999-12" }
        var url: String? =
            "$BASE/children/$childId/reports/?page_size=100&tz=$TZ&child=$childId"
        var cnt = 0
        while (url != null && downloadJob?.isActive == true) {
            val data    = apiGet(url)
            val results = data.optJSONArray("results") ?: break
            for (i in 0 until results.length()) {
                if (downloadJob?.isActive != true) break
                val rp  = results.getJSONObject(i)
                val dw  = rp.optString("date_written", "")
                val ym  = if (dw.length >= 7) dw.take(7) else continue
                if (ym < fromM || ym > toM) continue
                cnt++
                val day  = if (dw.length >= 10) dw.substring(8, 10) else "01"
                val imgs = rp.optJSONArray("attached_images")
                if (imgs == null || imgs.length() == 0) {
                    log("  $dw: 사진 없음"); continue
                }
                log("  $dw: ${imgs.length()}장")
                for (j in 0 until imgs.length()) {
                    val imgUrl = imgs.getJSONObject(j).optString("original", "")
                    if (imgUrl.isEmpty()) continue
                    dlFileSAF(imgUrl, "알림장/$ym/$day", "${rp.optInt("id")}_${j+1}.jpg")
                }
            }
            val next = data.optString("next")
            url = if (next.isEmpty() || next == "null") null else next
        }
        log("  알림장 총 ${cnt}건 처리")
    }

    // ── 앨범 다운로드 ─────────────────────────────────────────────────────────
    private fun downloadAlbums(childId: String, from: String, to: String) {
        log("앨범 다운로드...")
        val fromM = from.ifEmpty { "2010-01" }
        val toM   = to.ifEmpty   { "9999-12" }
        var url: String? =
            "$BASE/children/$childId/albums/?page_size=100&child=$childId"
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
                val imgs = al.optJSONArray("attached_images")
                    ?: al.optJSONArray("images") ?: continue
                log("  앨범/$ym/$day (${imgs.length()}장)")
                for (j in 0 until imgs.length()) {
                    val imgUrl = imgs.getJSONObject(j).let {
                        it.optString("original", it.optString("image", ""))
                    }
                    if (imgUrl.isEmpty()) continue
                    dlFileSAF(imgUrl, "앨범/$ym/$day", "${al.optInt("id")}_${j+1}.jpg")
                }
            }
            val next = data.optString("next")
            url = if (next.isEmpty() || next == "null") null else next
        }
    }

    // ── SAF 파일 저장 ─────────────────────────────────────────────────────────
    private fun dlFileSAF(url: String, subFolder: String, filename: String) {
        try {
            val baseUri = selectedFolderUri ?: throw Exception("저장 폴더 미설정")
            var dir = DocumentFile.fromTreeUri(this, baseUri)
                ?: throw Exception("폴더 접근 실패")

            for (seg in subFolder.split("/")) {
                if (seg.isEmpty()) continue
                dir = dir.findFile(seg)
                    ?: dir.createDirectory(seg)
                    ?: throw Exception("폴더 생성 실패: $seg")
            }

            val existing = dir.findFile(filename)
            if (existing != null && existing.length() > 0) {
                fileCount++; return
            }

            val mime = when (filename.substringAfterLast('.').lowercase()) {
                "mp4", "mov" -> "video/mp4"
                "png"        -> "image/png"
                else         -> "image/jpeg"
            }
            val newFile = dir.createFile(mime, filename)
                ?: throw Exception("파일 생성 실패")

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Cookie", cookies)
                setRequestProperty("Referer", "https://www.kidsnote.com/")
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 30000; readTimeout = 30000
            }
            contentResolver.openOutputStream(newFile.uri)?.use { out ->
                conn.inputStream.copyTo(out)
            }
            fileCount++
            log("  OK $filename")
        } catch (e: Exception) {
            errorCount++
            log("  NG $filename: ${e.message}")
        }
        Thread.sleep(200)
        updateStatus()
    }

    override fun onBackPressed() {
        if (loginView.visibility == View.VISIBLE && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
