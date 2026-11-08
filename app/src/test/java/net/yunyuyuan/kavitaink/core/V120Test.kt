package net.yunyuyuan.kavitaink.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class V120Test {
    private val EN = XmlTexts()
    private val ZH = XmlTexts("zh")

    private fun res(name: String): File = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    // ---------------- 多语言：卷 / 章节命名 ----------------

    private val looseJson = """
        [{"id":1,"name":"3","minNumber":3,"chapters":[
            {"id":10,"range":"21","title":"21","titleName":"决战","isSpecial":false,"pages":20,"sortOrder":21,"format":1,"files":[{"bytes":100,"extension":".cbz","format":1}]}]},
         {"id":2,"name":"-100000","minNumber":-100000,"chapters":[
            {"id":11,"range":"25","title":"25","titleName":null,"isSpecial":false,"pages":18,"sortOrder":25,"format":1,"files":[{"bytes":100,"extension":".cbr","format":1}]}]}]
    """.trimIndent()

    @Test
    fun titlesAreRenderedInTheCurrentLanguage() {
        val vols = Parsers.chapters(res("volumes_2.json").readText(), 2, Fmt.ARCHIVE)
        assertEquals("Volume 1", vols[0].title(EN))
        assertEquals("第1卷", vols[0].title(ZH))
        val list = Parsers.chapters(looseJson, 9, Fmt.ARCHIVE)
        assertEquals("Volume 3, Chapter 21 · 决战", list[0].title(EN))
        assertEquals("第3卷 第21话 · 决战", list[0].title(ZH))
        assertEquals("Chapter 25", list[1].title(EN))
        assertEquals("第25话", list[1].title(ZH))
        // 书名、文件名之类的原始标题不翻译
        val epub = Parsers.chapters(res("volumes_4.json").readText(), 4, Fmt.EPUB)
        assertEquals("Alice's Adventures in Wonderland", epub[0].title(EN))
    }

    @Test
    fun legacyChineseLabelsFrom110AreTranslated() {
        assertEquals("Volume 1, Chapter 3 · 标题", Labels.legacy("第1卷 第3话 · 标题", EN))
        assertEquals("Volume 2", Labels.legacy("第2卷", EN))
        assertEquals("Chapter 12", Labels.legacy("第12章", EN))
        assertEquals("Special", Labels.legacy("特别篇", EN))
        assertEquals("第1卷 第3话 · 标题", Labels.legacy("第1卷 第3话 · 标题", ZH))
        assertNull(Labels.legacy("Alice's Adventures in Wonderland", EN))
        assertNull(Labels.legacy("第一卷", EN))
        // 下载记录里没有结构化信息时走 legacy 识别
        assertEquals("Volume 1", Labels.render(0, null, "第1卷", EN))
        assertEquals("Some Book", Labels.render(0, null, "Some Book", EN))
    }

    @Test
    fun pluralsFollowLanguageRules() {
        assertEquals("one", XmlTexts.quantity("en", 1))
        assertEquals("other", XmlTexts.quantity("en", 2))
        assertEquals("other", XmlTexts.quantity("zh", 1))
        assertEquals("one", XmlTexts.quantity("fr", 0))
        assertEquals("few", XmlTexts.quantity("ru", 3))
        assertEquals("many", XmlTexts.quantity("ru", 11))
        assertEquals("one", XmlTexts.quantity("ru", 21))
    }

    // ---------------- 多语言：资源文件一致性 ----------------

    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXc%]")

    private fun placeholders(s: String): List<String> =
        placeholder.findAll(s).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test
    fun everyLanguageHasAllKeysWithMatchingPlaceholders() {
        val base = XmlTexts.load("")
        assertTrue("英文文案太少，可能没解析到", base.strings.size > 200)
        val problems = ArrayList<String>()
        // 英文本身的占位符必须带序号（翻译时可以调整语序）
        for ((k, v) in base.strings) {
            if (placeholders(v).size > 1 && placeholders(v).any { !it.contains('$') }) problems += "values/$k 多个占位符必须写成 %1\$s 形式"
        }
        val qs = XmlTexts.qualifiers().filter { it.isNotEmpty() }
        assertTrue("至少要有中文翻译", "zh" in qs)
        for (q in qs) {
            val r = XmlTexts.load(q)
            for ((k, v) in base.strings) {
                if (k in base.untranslatable) continue
                val t = r.strings[k]
                if (t == null) { problems += "values-$q 缺少 $k"; continue }
                if (placeholders(t) != placeholders(v)) problems += "values-$q/$k 占位符不一致：${placeholders(v)} vs ${placeholders(t)}"
            }
            for (k in r.strings.keys) if (k !in base.strings) problems += "values-$q 多余的键 $k"
            for (k in r.strings.keys) if (k in base.untranslatable) problems += "values-$q 不应翻译 $k"
            for ((k, m) in base.plurals) {
                val t = r.plurals[k]
                if (t == null) { problems += "values-$q 缺少复数 $k"; continue }
                if ("other" !in t) problems += "values-$q/$k 缺少 other"
                val want = placeholders(m["other"]!!)
                for ((qty, s) in t) if (placeholders(s) != want) problems += "values-$q/$k[$qty] 占位符不一致"
            }
            for (k in r.plurals.keys) if (k !in base.plurals) problems += "values-$q 多余的复数 $k"
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun noHardCodedChineseInSources() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        // 这两个文件里的中文是有意的：识别 1.1.0 的中文标题；语言列表用各语言自己的名字
        val allow = setOf("Texts.kt", "Lang.kt")
        val cjk = Regex("[\\u3000-\\u303f\\u4e00-\\u9fff\\uff00-\\uffef]")
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val bad = ArrayList<String>()
        src.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name !in allow }.forEach { f ->
            var inBlock = false
            f.readLines().forEachIndexed { i, raw ->
                var line = raw
                if (inBlock) {
                    val end = line.indexOf("*/")
                    if (end < 0) return@forEachIndexed
                    line = line.substring(end + 2)
                    inBlock = false
                }
                line = line.replace(Regex("/\\*.*?\\*/"), "")
                val open = line.indexOf("/*")
                if (open >= 0 && !line.substring(0, open).contains('"')) {
                    line = line.substring(0, open)
                    inBlock = true
                }
                literal.findAll(line.substringBefore("// ").let { if (it.count { c -> c == '"' } % 2 == 0) it else line })
                    .filter { cjk.containsMatchIn(it.value) }
                    .forEach { bad += "${f.name}:${i + 1}: ${it.value}" }
            }
        }
        if (bad.isNotEmpty()) fail("界面文案应放进 strings.xml：\n" + bad.joinToString("\n"))
    }

    // ---------------- F7 裁白边 ----------------

    private fun page(w: Int, h: Int, bg: Int, l: Int, t: Int, r: Int, b: Int, fg: Int = 0xFF000000.toInt()): IntArray {
        val px = IntArray(w * h) { bg }
        for (y in t until b) for (x in l until r) px[y * w + x] = fg
        return px
    }

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
    private val GRAY = 0xFF808080.toInt()

    @Test
    fun trimFindsContentWithSafetyMargin() {
        val w = 200
        val h = 300
        val b = Trim.detect(page(w, h, WHITE, 30, 40, 170, 260, GRAY), w, h)
        assertNotNull(b)
        b!!
        // 内容 30..170 × 40..260，四周各留 1% 安全边距（宽 2px，高 3px）
        assertEquals(28, b.left)
        assertEquals(37, b.top)
        assertEquals(172, b.right)
        assertEquals(263, b.bottom)
    }

    @Test
    fun trimNeverCutsMoreThanAQuarterPerSide() {
        val w = 200
        val h = 200
        // 内容只占中间一小块：每边最多裁 25%，裁完面积 = (0.5+2%)² ≈ 27% < 40%，放弃
        assertNull(Trim.detect(page(w, h, WHITE, 90, 90, 110, 110), w, h))
        // 左边留白很宽（35%），只裁到 25%
        val b = Trim.detect(page(w, h, WHITE, 70, 0, 200, 200), w, h)!!
        assertEquals(48, b.left)
        assertEquals(0, b.top)
        assertEquals(200, b.right)
    }

    @Test
    fun trimLeavesFullBleedAndBlankPagesAlone() {
        val w = 100
        val h = 100
        assertNull("满版画面不裁", Trim.detect(page(w, h, GRAY, 0, 0, w, h), w, h))
        assertNull("全白页不裁", Trim.detect(IntArray(w * h) { WHITE }, w, h))
        // 黑边：默认不裁，开启「同时裁黑边」才裁
        val px = page(w, h, BLACK, 10, 10, 90, 90, GRAY)
        assertNull(Trim.detect(px, w, h, black = false))
        val b = Trim.detect(px, w, h, black = true)!!
        assertEquals(Box(9, 9, 91, 91), b)
    }

    @Test
    fun trimToleratesSpecksOnTheMargin() {
        val w = 400
        val h = 400
        val px = page(w, h, WHITE, 60, 60, 340, 340, GRAY)
        // 白边上有 1 个噪点（< 0.5%）仍算白边
        px[10 * w + 5] = BLACK
        val b = Trim.detect(px, w, h)!!
        assertEquals(56, b.left)
        assertEquals(56, b.top)
    }

    @Test
    fun boxScalesBackToOriginalSize() {
        assertEquals(Box(100, 150, 900, 1350), Box(10, 15, 90, 135).scale(100, 150, 1000, 1500))
    }

    // ---------------- F3 双页拼页 ----------------

    private fun groups(u: List<IntArray>) = u.map { it.toList() }

    @Test
    fun spreadsPairPagesWithCoverAlone() {
        val a = FloatArray(6) { 0.7f }
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), groups(Spreads.build(a, coverAlone = true)))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), groups(Spreads.build(a, coverAlone = false)))
    }

    @Test
    fun widePagesStayAlone() {
        val a = floatArrayOf(0.7f, 0.7f, 0.7f, 1.4f, 0.7f, 0.7f)
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3), listOf(4, 5)), groups(Spreads.build(a, coverAlone = true)))
        // 宽图前面落单的一页单独显示，不和宽图配对
        val b = floatArrayOf(0.7f, 0.7f, 1.4f, 0.7f)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), groups(Spreads.build(b, coverAlone = true)))
    }

    @Test
    fun shiftMovesPairingByOnePage() {
        val a = FloatArray(6) { 0.7f }
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4, 5)), groups(Spreads.build(a, coverAlone = true, shift = true)))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), groups(Spreads.build(a, coverAlone = false, shift = true)))
    }

    @Test
    fun trimDetectIsCheapOnProbeSizedImage() {
        // 800x1150 的页面按 1/4 解码后是 200x287；四周白边、中间是画面
        val w = 200
        val h = 288
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 40..160 && y in 50..240) (0xFF shl 24) or (((x * 7 + y * 3) % 200) * 0x010101) else -1
        }
        repeat(50) { Trim.detect(px, w, h, black = true) } // 预热 JIT
        val n = 200
        val t0 = System.nanoTime()
        repeat(n) { Trim.detect(px, w, h, black = true) }
        val avgMs = (System.nanoTime() - t0) / 1e6 / n
        println("Trim.detect ${w}x$h 平均 ${"%.3f".format(avgMs)} ms")
        assertNotNull(Trim.detect(px, w, h))
        assertTrue("Trim.detect 平均 $avgMs ms", avgMs < 5.0)
    }

    /** ARGB → RGB565 原始像素（Bitmap.copyPixelsToBuffer 的格式） */
    private fun to565(px: IntArray): ShortArray = ShortArray(px.size) { i ->
        val c = px[i]
        ((((c shr 16) and 0xFF) shr 3 shl 11) or (((c shr 8) and 0xFF) shr 2 shl 5) or ((c and 0xFF) shr 3)).toShort()
    }

    /** ARGB → ARGB_8888 原始像素按小端读成的 Int（A<<24 | B<<16 | G<<8 | R） */
    private fun toRgba(px: IntArray): IntArray = IntArray(px.size) { i ->
        val c = px[i]
        (c and 0xFF00FF00.toInt()) or ((c shr 16) and 0xFF) or ((c and 0xFF) shl 16)
    }

    @Test
    fun rawPixelBlocksGiveTheSameCropAsFullImage() {
        val w = 1072
        val h = 1448
        val px = page(w, h, WHITE, 90, 120, 980, 1330, GRAY)
        val full = Trim.detect(px, w, h)!!
        for ((name, src) in listOf(
            "565" to RawLum.rgb565(to565(px), w, w, h, RawLum.blockFor(w, h, 400)),
            "8888" to RawLum.rgba8888(toRgba(px), w, w, h, RawLum.blockFor(w, h, 400)),
        )) {
            assertEquals(4, RawLum.blockFor(w, h, 400))
            assertEquals(268, src.w)
            assertEquals(362, src.h)
            val b = Trim.detect(src, false)!!
            // 换算回原图坐标后，和在原图上检测的结果相差不超过一个块（4 px）加上安全边距的取整
            val k = w.toDouble() / src.w
            assertEquals("$name left", full.left.toDouble(), b.left * k, 8.0)
            assertEquals("$name top", full.top.toDouble(), b.top * k, 8.0)
            assertEquals("$name right", full.right.toDouble(), b.right * k, 8.0)
            assertEquals("$name bottom", full.bottom.toDouble(), b.bottom * k, 8.0)
            // 永远不会裁进画面
            assertTrue(b.left * k <= 90 && b.top * k <= 120 && b.right * k >= 980 && b.bottom * k >= 1330)
        }
    }

    @Test
    fun thinLinesInTheMarginSurviveBlockAveraging() {
        // 白边里有一条 1 px 的细横线（比如页眉线）：块平均后变灰（191 < 235），仍算作内容，不会被裁掉
        val w = 800
        val h = 1150
        val px = page(w, h, WHITE, 80, 200, 720, 1000, GRAY)
        for (x in 80 until 720) px[101 * w + x] = 0xFF000000.toInt()
        val src = RawLum.rgb565(to565(px), w, w, h, RawLum.blockFor(w, h, 400))
        val b = Trim.detect(src, false)!!
        val k = h.toDouble() / src.h
        assertTrue("细线以上才裁：top=${b.top * k}", b.top * k <= 101)
    }

    @Test
    fun rgbaByteOrderIsReadCorrectly() {
        val red = RawLum.rgba8888(IntArray(64) { 0xFF0000FF.toInt() }, 8, 8, 8, 1)  // 内存 R=FF G=00 B=00 A=FF
        val blue = RawLum.rgba8888(IntArray(64) { 0xFFFF0000.toInt() }, 8, 8, 8, 1)
        val out = IntArray(8)
        red.row(0, out)
        assertEquals(255 * 299 / 1000, out[0])
        blue.row(0, out)
        assertEquals(255 * 114 / 1000, out[0])
        val white565 = RawLum.rgb565(ShortArray(64) { 0xFFFF.toShort() }, 8, 8, 8, 1)
        white565.col(3, out, 0, 8)
        assertEquals(255, out[0])
    }

    /** 模拟 PageView 的预检：按 Bitmap.getPixels 的方式截出最外侧四条细边（ARGB） */
    private fun outerCheck(px: IntArray, w: Int, h: Int, black: Boolean): Boolean {
        val f = RawLum.blockFor(w, h, 400)
        fun strip(x0: Int, y0: Int, sw: Int, sh: Int) = IntArray(sw * sh) { i -> px[(y0 + i / sw) * w + x0 + i % sw] }
        val tb = minOf(f, h)
        val bb = h - ((h + f - 1) / f - 1) * f
        val lb = minOf(f, w)
        val rb = w - ((w + f - 1) / f - 1) * f
        return Trim.outerEdgesAreContent(
            { RawLum.argb(strip(0, 0, w, tb), w, w, tb, f) },
            { RawLum.argb(strip(0, h - bb, w, bb), w, w, bb, f) },
            { RawLum.argb(strip(0, 0, lb, h), lb, lb, h, f) },
            { RawLum.argb(strip(w - rb, 0, rb, h), rb, rb, h, f) },
            black,
        )
    }

    @Test
    fun outerEdgeCheckAgreesWithDetect() {
        val rnd = java.util.Random(7)
        var skipped = 0
        repeat(300) { n ->
            // 尺寸故意取奇数 / 非块整数倍，边距 0~20%，有的页贴边
            val w = 300 + rnd.nextInt(900)
            val h = 400 + rnd.nextInt(1100)
            val bg = if (rnd.nextInt(4) == 0) BLACK else WHITE
            fun m(size: Int) = if (rnd.nextInt(3) == 0) 0 else rnd.nextInt(size / 5 + 1)
            val l = m(w)
            val t = m(h)
            val r = w - m(w)
            val b = h - m(h)
            val px = page(w, h, bg, l, t, r, b, GRAY)
            if (rnd.nextBoolean()) for (x in 0 until w) px[(t + (b - t) / 2) * w + x] = BLACK // 横穿画面的细线
            for (black in listOf(false, true)) {
                val skip = outerCheck(px, w, h, black)
                val raw = RawLum.rgb565(to565(px), w, w, h, RawLum.blockFor(w, h, 400))
                if (skip) {
                    skipped++
                    assertNull("#$n ${w}x$h 边距 $l,$t,$r,$b black=$black：预检说不裁，detect 也必须不裁", Trim.detect(raw, black))
                }
            }
        }
        assertTrue("应有相当一部分满版页被预检跳过：$skipped", skipped > 50)
        // 明确的两种情况
        assertTrue(outerCheck(page(800, 1150, WHITE, 0, 0, 800, 1150, GRAY), 800, 1150, false))
        assertFalse(outerCheck(page(800, 1150, WHITE, 160, 0, 800, 1150, GRAY), 800, 1150, false)) // 只有左边白边
        assertFalse(outerCheck(page(800, 1150, BLACK, 0, 0, 800, 1100, GRAY), 800, 1150, true))    // 只有下边黑边，裁黑边
        assertTrue(outerCheck(page(800, 1150, BLACK, 0, 0, 800, 1100, GRAY), 800, 1150, false))     // 不裁黑边时它就是满版
    }

    @Test
    fun argbOrderMatchesRgbaOrder() {
        val w = 64
        val h = 64
        val px = IntArray(w * h) { i -> if (i % 3 == 0) 0xFFFF0000.toInt() else if (i % 3 == 1) 0xFF0000FF.toInt() else 0xFF20C040.toInt() }
        val a = RawLum.argb(px, w, w, h, 4)
        val b = RawLum.rgba8888(toRgba(px), w, w, h, 4)
        val oa = IntArray(16)
        val ob = IntArray(16)
        for (y in 0 until 16) {
            a.row(y, oa)
            b.row(y, ob)
            assertArrayEquals(ob, oa)
        }
    }

    @Test
    fun rawPixelDetectIsCheap() {
        val w = 1072
        val h = 1448
        val raw = to565(page(w, h, WHITE, 90, 120, 980, 1330, GRAY))
        val f = RawLum.blockFor(w, h, 400)
        repeat(30) { Trim.detect(RawLum.rgb565(raw, w, w, h, f), false) } // 预热 JIT
        val n = 100
        val t0 = System.nanoTime()
        repeat(n) { Trim.detect(RawLum.rgb565(raw, w, w, h, f), false) }
        val avgMs = (System.nanoTime() - t0) / 1e6 / n
        println("RawLum ${w}x$h（白边 8%）平均 ${"%.3f".format(avgMs)} ms")
        assertTrue("平均 $avgMs ms", avgMs < 10.0)
    }

    @Test
    fun shiftAlsoAppliesAfterWidePages() {
        // 和测试漫画 Spread Test 一样：10 页，第 1 页封面，第 6 页横图
        val a = FloatArray(10) { if (it == 5) 1.45f else 0.7f }
        assertEquals(
            listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5), listOf(6, 7), listOf(8, 9)),
            groups(Spreads.build(a, coverAlone = true)),
        )
        // 横图后面那一段也要错开，否则在那一段里打开开关看不到任何变化
        assertEquals(
            listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6), listOf(7, 8), listOf(9)),
            groups(Spreads.build(a, coverAlone = true, shift = true)),
        )
        // 每一页都恰好出现一次，顺序不变
        for (shift in listOf(false, true)) for (cover in listOf(false, true)) {
            assertEquals((0 until 10).toList(), Spreads.build(a, cover, shift).flatMap { it.toList() })
        }
    }

    @Test
    fun spreadIndexFindsGroupOfPage() {
        val u = Spreads.build(FloatArray(6) { 0.7f }, coverAlone = true)
        assertEquals(0, Spreads.indexOf(u, 0))
        assertEquals(1, Spreads.indexOf(u, 2))
        assertEquals(3, Spreads.indexOf(u, 5))
        assertEquals(0, Spreads.indexOf(u, 99))
        assertArrayEquals(intArrayOf(3), Spreads.single(5)[3])
        assertTrue(Spreads.build(FloatArray(0), coverAlone = true).isEmpty())
    }

    // ---------------- F2 字体索引 ----------------

    private fun fakeTtf(): ByteArray = byteArrayOf(0, 1, 0, 0) + ByteArray(60) { it.toByte() }

    @Test
    fun fontStoreImportRenameRemove() {
        val dir = Files.createTempDirectory("fonts").toFile()
        try {
            val store = FontStore(dir)
            val f = store.importLocal(ByteArrayInputStream(fakeTtf()), "/sdcard/Fonts/My Font.ttf")
            assertEquals("My Font", f.name)
            assertEquals(FontStore.SOURCE_LOCAL, f.source)
            assertEquals("font.ttf", f.variants.single().file)
            // 同一文件重复导入只保留一份
            store.importLocal(ByteArrayInputStream(fakeTtf()), "copy.ttf")
            assertEquals(1, store.list().size)

            // Kavita 字体：多个变体
            val kd = store.familyDir("kavita-fira")
            kd.mkdirs()
            File(kd, "a.woff2").writeBytes(ByteArray(10))
            File(kd, "b.woff2").writeBytes(ByteArray(20))
            store.putKavita(
                "kavita-fira", "Fira Sans", "Fira Sans",
                listOf(FontStore.Variant("a.woff2"), FontStore.Variant("b.woff2", "italic", "700")),
            )
            store.rename(f.id, "  我的字体  ")

            // 重新读取索引（模拟重启）
            val again = FontStore(dir)
            assertEquals(listOf("Fira Sans", "我的字体"), again.list().map { it.name })
            val fira = again.get("kavita-fira")!!
            assertEquals(30L, fira.bytes)
            assertEquals("italic", fira.variants[1].style)
            assertEquals("Fira Sans", fira.remote)

            // 变体文件丢失时自动忽略
            File(kd, "b.woff2").delete()
            assertEquals(1, again.get("kavita-fira")!!.variants.size)

            again.remove(f.id)
            assertNull(again.get(f.id))
            assertFalse(again.familyDir(f.id).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fontImportRejectsNonFonts() {
        val dir = Files.createTempDirectory("fonts").toFile()
        try {
            val store = FontStore(dir)
            try {
                store.importLocal(ByteArrayInputStream("not a font at all, really".toByteArray()), "x.ttf")
                fail("应拒绝非字体文件")
            } catch (e: FontStore.BadFontException) {
                assertEquals("Not a supported font file", EN.error(e).substringBefore(" ("))
            }
            assertTrue(store.list().isEmpty())
            assertFalse(File(dir, "import.part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------- 检查更新 ----------------

    private val releaseJson = """
        {"tag_name":"v1.2.0","html_url":"https://github.com/yunyuyuan/KavitaInk/releases/tag/v1.2.0",
         "body":"## 新功能\n- **双页** 显示\n- 字体 [说明](https://example.com)\n",
         "assets":[
           {"name":"SHA256SUMS.txt","size":100,"browser_download_url":"https://x/SHA256SUMS.txt"},
           {"name":"KavitaInk-1.2.0-debugsigned.apk","size":200,"browser_download_url":"https://x/debug.apk"},
           {"name":"KavitaInk-1.2.0.apk","size":123456,"browser_download_url":"https://x/KavitaInk-1.2.0.apk"}]}
    """.trimIndent()

    @Test
    fun parsesLatestRelease() {
        val r = Updater.parse(releaseJson)
        assertEquals("v1.2.0", r.tag)
        assertEquals("1.2.0", r.version)
        assertEquals("KavitaInk-1.2.0.apk", r.apkName)
        assertEquals("https://x/KavitaInk-1.2.0.apk", r.apkUrl)
        assertEquals(123456L, r.apkSize)
        assertEquals("新功能\n• 双页 显示\n• 字体 说明", Updater.plainNotes(r.notes))
    }

    @Test
    fun releaseWithoutApkIsAnError() {
        try {
            Updater.parse("""{"tag_name":"v9.0.0","assets":[{"name":"notes.txt","browser_download_url":"u"}]}""")
            fail()
        } catch (e: ApiException) {
            assertEquals("No installer was found in release v9.0.0", EN.error(e))
            assertEquals("版本 v9.0.0 中没有找到安装包", ZH.error(e))
        }
    }

    @Test
    fun versionComparisonIgnoresBuildSuffix() {
        assertTrue(Updater.isNewer("1.2.0", "1.1.0"))
        assertTrue(Updater.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.2.0", "1.2.0"))
        // CI 的开发构建 1.2.0-abc1234 与正式版 1.2.0 视为同一版本（和 versionCode 规则一致）
        assertFalse(Updater.isNewer("1.2.0", "1.2.0-abc1234"))
        assertFalse(Updater.isNewer("1.1.0", "1.2.0"))
        assertEquals(listOf(1, 2, 0), Updater.numbers("v1.2"))
    }

    @Test
    fun assetNamesAreSafeFileNames() {
        assertEquals("KavitaInk-1.2.0.apk", Updater.safeName("KavitaInk-1.2.0.apk"))
        val s = Updater.safeName("../../evil name.apk")
        assertFalse(s.contains('/'))
        assertFalse(s.startsWith("."))
        assertTrue(s.endsWith(".apk"))
        assertTrue(Updater.safeName("weird").endsWith(".apk"))
    }

    @Test
    fun customLayoutSkipsCenteredAndImageParagraphs() {
        val custom = Paginator.Style(bookStyle = false, textIndent = 4)
        val css = Paginator.css(custom, 400, 600)
        // 自定义规则只在分页脚本标记完之后（html.__einkx）生效，并跳过 __einkn 段落
        assertTrue(css.contains("html.__einkx p:not(.__einkn){text-indent:4em !important;"))
        assertTrue(css.contains("html.__einkx p.__einkn{text-indent:0 !important;}"))
        assertFalse(Regex("(^|[};])p\\{text-indent").containsMatchIn(css))
        assertFalse(css.contains("<") || css.contains("&"))
        val js = Paginator.script(1, custom)
        assertTrue(js.contains("__einkn") && js.contains("__einkx") && js.contains("textAlign"))
        // 书籍原排版：不注入缩进 / 对齐规则，也不做标记
        val book = Paginator.Style(bookStyle = true)
        assertFalse(Paginator.css(book, 400, 600).contains("__einkx"))
        assertTrue(Paginator.script(1, book).contains("if(0)"))
        // 标记脚本随样式注入 <head>（DOMContentLoaded 时执行，排版前标记完）；脚本体里不能有 < 和 &（XHTML）
        val head = Paginator.headTemplate(custom)
        val body = head.substringAfter("<script id=\"__einkmark\" type=\"text/javascript\">").substringBefore("</script>")
        assertTrue(body.contains("DOMContentLoaded") && body.contains("__einkx"))
        assertFalse(body.contains("<") || body.contains("&"))
        assertFalse(Paginator.headTemplate(book).contains("<script"))
        // 两种模式都允许超长的词断行
        for (st in listOf(custom, book)) assertTrue(Paginator.css(st, 400, 600).contains("overflow-wrap:break-word"))
    }
}
