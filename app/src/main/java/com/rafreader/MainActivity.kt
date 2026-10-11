package com.rafreader

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Html
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.GestureDetector
import android.widget.*
import android.provider.OpenableColumns
import android.app.AlertDialog
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream
import android.os.ParcelFileDescriptor

class MainActivity : Activity() {
    private val ink = Color.rgb(42, 50, 44)
    private val paper = Color.rgb(250, 248, 241)
    private val green = Color.rgb(48, 91, 71)

    private lateinit var root: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences

    private var dark = false
    private var fontSize = 20f
    private var activeUri: Uri? = null

    private var pdf: PdfRenderer? = null
    private var pdfPage = 0
    private var pdfZoom = 1f
    private var pdfImage: ImageView? = null
    private var pdfVerticalScroller: ScrollView? = null
    private var pdfHorizontalScroller: HorizontalScrollView? = null

    private var textScroll: ScrollView? = null
    private var titleNow = ""
    private var textBody: TextView? = null
    private var bookText = ""

    private val pickCode = 81
    private var searchFrom = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("reader", MODE_PRIVATE)
        dark = prefs.getBoolean("night", false)
        fontSize = prefs.getFloat("font", 20f)

        showHome()
    }

    override fun onDestroy() {
        pdf?.close()
        pdf = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun bg(): Int =
        if (dark) Color.rgb(30, 33, 31) else paper

    private fun fg(): Int =
        if (dark) Color.rgb(236, 232, 218) else ink

    private fun muted(): Int =
        if (dark) 0xffaeb5ae.toInt() else 0xff85877f.toInt()

    private fun uriKey(uri: Uri): String =
        uri.toString().hashCode().toString()

    private fun column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun label(
        text: String,
        size: Float,
        color: Int = fg(),
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.RIGHT

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            setLineSpacing(dp(5).toFloat(), 1f)
        }

    private fun button(
        text: String,
        primary: Boolean = false,
        action: () -> Unit
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(17), dp(13), dp(17), dp(13))

            setTextColor(if (primary) Color.WHITE else fg())

            background =
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(
                        when {
                            primary -> green
                            dark -> Color.rgb(52, 57, 53)
                            else -> Color.rgb(239, 236, 226)
                        }
                    )
                }

            setOnClickListener { action() }
        }

    private fun base(home: Boolean = false) {
        root = column()
        root.setBackgroundColor(bg())

        if (home) {
            setContentView(
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(root)
                }
            )
        } else {
            setContentView(root)
        }
    }

    private fun gap(height: Int) {
        root.addView(
            View(this),
            LinearLayout.LayoutParams(1, dp(height))
        )
    }

    private fun showHome() {
        activeUri = null
        base(home = true)

        root.addView(label("رَفّ", 31f, green, true))
        root.addView(label("مساحتك الهادئة للقراءة", 15f, muted()))
        gap(23)

        val hero =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))

                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(22).toFloat()
                        setColor(
                            if (dark) {
                                0xff34443a.toInt()
                            } else {
                                0xffe6ebdf.toInt()
                            }
                        )
                    }
            }

        hero.addView(
            label(
                "اقرأ على طريقتك",
                22f,
                if (dark) 0xfff1f2e9.toInt() else green,
                true
            )
        )

        hero.addView(
            TextView(this).apply {
                text = "افتح كتب PDF وEPUB وTXT من جهازك، واضبط حجم الخط والسطوع براحتك."
                textSize = 15f
                setTextColor(
                    if (dark) 0xffd6ddd3.toInt() else 0xff59635a.toInt()
                )
                gravity = Gravity.RIGHT
                setPadding(0, dp(9), 0, dp(15))
                setLineSpacing(dp(4).toFloat(), 1f)
            }
        )

        hero.addView(
            button("＋   افتح كتابًا من الجهاز", true) {
                chooseBook()
            }
        )

        root.addView(hero)
        gap(26)

        root.addView(label("كتبك الأخيرة", 20f, fg(), true))
        gap(8)

        val recent =
            prefs.getString("recent", "").orEmpty()
                .split("\n")
                .filter { it.isNotBlank() }
                .distinct()
                .take(8)

        if (recent.isEmpty()) {
            root.addView(
                label("ستظهر هنا الكتب التي فتحتها مؤخرًا.", 15f, muted())
            )
        } else {
            recent.forEach { raw ->
                try {
                    val uri = Uri.parse(raw)
                    val name = displayName(uri)

                    val card =
                        TextView(this).apply {
                            text = "▤   $name\n       اضغط للمتابعة"
                            textSize = 15f
                            setTextColor(fg())
                            gravity = Gravity.RIGHT
                            setPadding(dp(15), dp(14), dp(15), dp(14))

                            background =
                                android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = dp(15).toFloat()
                                    setColor(
                                        if (dark) 0xff343935.toInt()
                                        else Color.WHITE
                                    )
                                }

                            setOnClickListener {
                                openBook(uri)
                            }
                        }

                    val layoutParams = LinearLayout.LayoutParams(-1, -2)
                    layoutParams.bottomMargin = dp(9)
                    root.addView(card, layoutParams)
                } catch (_: Exception) {
                }
            }
        }

        gap(18)

        root.addView(
            button(
                if (dark) "☼   الوضع النهاري"
                else "☾   الوضع الليلي"
            ) {
                dark = !dark
                prefs.edit().putBoolean("night", dark).apply()
                showHome()
            }
        )

        gap(12)

        root.addView(
            label("يدعم PDF  ·  EPUB  ·  TXT", 13f, muted())
        )
    }

    private fun chooseBook() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/pdf",
                        "application/epub+zip",
                        "text/plain",
                        "application/octet-stream"
                    )
                )
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        startActivityForResult(intent, pickCode)
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                openBook(uri)
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "كتاب"
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
            }
        }
        return name
    }

    private fun remember(uri: Uri) {
        val old =
            prefs.getString("recent", "").orEmpty()
                .split("\n")
                .filter { it.isNotBlank() && it != uri.toString() }

        prefs.edit()
            .putString(
                "recent",
                (listOf(uri.toString()) + old).take(8).joinToString("\n")
            )
            .apply()
    }

    private fun openBook(uri: Uri) {
        try {
            pdf?.close()
            pdf = null

            activeUri = uri
            titleNow = displayName(uri)
            remember(uri)
            searchFrom = 0

            val mime = contentResolver.getType(uri)?.lowercase().orEmpty()
            val extension = titleNow.substringAfterLast('.', "").lowercase()

            when {
                mime.contains("pdf") || extension == "pdf" -> {
                    openPdf(uri)
                }
                mime.contains("epub") || extension == "epub" -> {
                    openText(readEpub(uri))
                }
                else -> {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("تعذر فتح الملف")
                    val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    openText(text)
                }
            }
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "تعذر فتح الملف. تأكد من أنه PDF أو EPUB أو TXT.",
                Toast.LENGTH_LONG
            ).show()
            showHome()
        }
    }

    private fun openPdf(uri: Uri) {
        try {
            val pfd: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pdf = PdfRenderer(pfd)
                pdfPage = 0
                pdfZoom = 1f
                showPdfViewer()
            } else {
                throw IllegalArgumentException("تعذر فتح ملف PDF")
            }
        } catch (_: Exception) {
            Toast.makeText(this, "خطأ في قراءة ملف PDF", Toast.LENGTH_LONG).show()
            showHome()
        }
    }

    private fun showPdfViewer() {
        base()
        val renderer = pdf ?: return

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(button("‹ رجوع") { showHome() }, LinearLayout.LayoutParams(-2, -2))
        val title = label(titleNow, 15f, fg(), true)
        title.gravity = Gravity.CENTER
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        gap(14)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        controls.addView(button("‹ السابق") {
            if (pdfPage > 0) {
                pdfPage--
                renderPdfPage()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val pageLabel = label("صفحة ${pdfPage + 1} من ${renderer.pageCount}", 14f, fg(), true)
        pageLabel.gravity = Gravity.CENTER
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, -2, 1f))

        controls.addView(button("التالي ›") {
            if (pdfPage < renderer.pageCount - 1) {
                pdfPage++
                renderPdfPage()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(controls)
        gap(10)

        pdfImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        pdfVerticalScroller = ScrollView(this).apply {
            setBackgroundColor(bg())
            addView(pdfImage, LinearLayout.LayoutParams(-1, -2))
        }

        pdfHorizontalScroller = HorizontalScrollView(this).apply {
            addView(pdfVerticalScroller, LinearLayout.LayoutParams(-1, -1))
        }

        root.addView(pdfHorizontalScroller, LinearLayout.LayoutParams(-1, 0, 1f))
        renderPdfPage()
    }

    private fun renderPdfPage() {
        val renderer = pdf ?: return
        if (pdfPage < 0 || pdfPage >= renderer.pageCount) return
        try {
            val page = renderer.openPage(pdfPage)
            val width = (page.width * 1.5f * pdfZoom).toInt()
            val height = (page.height * 1.5f * pdfZoom).toInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            pdfImage?.setImageBitmap(bitmap)
        } catch (_: Exception) {
        }
    }

    private fun readEpub(uri: Uri): String {
        val sb = StringBuilder()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipStream ->
                    var entry = zipStream.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml") || entry.name.endsWith(".htm"))) {
                            val bytes = ByteArrayOutputStream()
                            val buffer = ByteArray(1024)
                            var len: Int
                            while (zipStream.read(buffer).also { len = it } != -1) {
                                bytes.write(buffer, 0, len)
                            }
                            val html = bytes.toString(Charsets.UTF_8.name())
                            sb.append(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()).append("\n\n")
                        }
                        zipStream.closeEntry()
                        entry = zipStream.nextEntry
                    }
                }
            }
        } catch (_: Exception) {
        }
        val res = sb.toString()
        return if (res.isNotBlank()) res else "محتوى EPUB فارغ أو غير مدعوم."
    }

    private fun openText(raw: String) {
        bookText = raw
        base()

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        top.addView(button("‹ رجوع") { showHome() }, LinearLayout.LayoutParams(-2, -2))
        val title = label(titleNow, 15f, fg(), true)
        title.gravity = Gravity.CENTER
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)
        gap(14)

        root.addView(
            label(
                "حجم الخط",
                13f,
                if (dark) 0xffaeb5ae.toInt() else 0xff777a72.toInt()
            )
        )

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }

        controls.addView(button("− تصغير") {
            fontSize = (fontSize - 2).coerceAtLeast(14f)
            prefs.edit().putFloat("font", fontSize).apply()
            activeUri?.let { openBook(it) }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        controls.addView(TextView(this).apply {
            text = fontSize.toInt().toString()
            textSize = 16f
            setTextColor(fg())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(40), -2))

        controls.addView(button("＋ تكبير") {
            fontSize = (fontSize + 2).coerceAtMost(34f)
            prefs.edit().putFloat("font", fontSize).apply()
            activeUri?.let { openBook(it) }
        }, LinearLayout.LayoutParams(0, -2, 1f))

        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        controls.addView(button(if (dark) "☼" else "☾") {
            dark = !dark
            prefs.edit().putBoolean("night", dark).apply()
            activeUri?.let { openBook(it) }
        }, LinearLayout.LayoutParams(dp(58), -2))

        root.addView(controls)
        gap(13)

        val key = "scroll_${uriKey(activeUri!!)}"
        val scroller = ScrollView(this).apply {
            setBackgroundColor(bg())
        }

        val body = TextView(this).apply {
            text = raw
            textSize = fontSize
            setTextColor(fg())
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(dp(9).toFloat(), 1.15f)
            setPadding(dp(5), dp(8), dp(5), dp(25))
            setTextIsSelectable(false)
        }

        textBody = body
        scroller.addView(body)

        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        textScroll = scroller

        val savedPosition = prefs.getInt(key, 0)
        if (savedPosition > 0) {
            scroller.post { scroller.scrollTo(0, savedPosition) }
        }

        scroller.setOnScrollChangeListener { _, _, y, _, _ ->
            prefs.edit().putInt(key, y).apply()
        }

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        tools.addView(button("بحث") { searchText() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button("＋ علامة") { addBookmark() }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button("علاماتي") { showBookmarks() }, LinearLayout.LayoutParams(0, -2, 1f))

        root.addView(tools)
        installTextTouch(scroller, body)
    }

    private fun installTextTouch(scroller: ScrollView, body: TextView) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
               
