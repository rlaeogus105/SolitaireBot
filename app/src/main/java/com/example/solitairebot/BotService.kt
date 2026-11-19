package com.example.solitairebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

class BotService : AccessibilityService() {
    companion object {
        const val PREFS = "card_pilot"
        const val KEY_RUNNING = "running"
        private const val LOOP_MS = 1500L
    }

    private data class Word(val rank: Int, val bounds: Rect)
    private data class Plan(val length: Int, val firstRank: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val koreanRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private var busy = false
    private var boundGamePackage: String? = null
    private var lastActionAt = 0L
    private var overlayButton: Button? = null

    private val loop = object : Runnable {
        override fun run() {
            if (isRunning()) capture()
            handler.postDelayed(this, LOOP_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = if (isRunning()) "■ 정지" else "▶ 시작"
            isAllCaps = false
            setOnClickListener {
                val enabled = !isRunning()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, enabled).apply()
                text = if (enabled) "■ 정지" else "▶ 시작"
                if (enabled) boundGamePackage = null
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 8 }
        overlayButton = button
        wm.addView(button, params)
        handler.post(loop)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { stopAutomation() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        latinRecognizer.close()
        koreanRecognizer.close()
        overlayButton?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        super.onDestroy()
    }

    private fun isRunning() = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

    private fun stopAutomation() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        overlayButton?.text = "▶ 시작"
    }

    private fun capture() {
        if (busy || Build.VERSION.SDK_INT < 30) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                try {
                    val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val bitmap = hw?.copy(Config.ARGB_8888, false)
                    hw?.recycle()
                    if (bitmap == null) { busy = false; return }
                    analyze(bitmap)
                } catch (_: Exception) {
                    busy = false
                } finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun analyze(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val activePackage = rootInActiveWindow?.packageName?.toString()
        val input = InputImage.fromBitmap(bitmap, 0)
        latinRecognizer.process(input).addOnSuccessListener { latin ->
            koreanRecognizer.process(input).addOnSuccessListener { korean ->
                decide(bitmap, latin, korean.text, width, height, activePackage)
                bitmap.recycle()
                busy = false
            }.addOnFailureListener {
                decide(bitmap, latin, "", width, height, activePackage)
                bitmap.recycle()
                busy = false
            }
        }.addOnFailureListener {
            bitmap.recycle()
            busy = false
        }
    }

    private fun decide(bitmap: Bitmap, latin: Text, korean: String, width: Int, height: Int, activePackage: String?) {
        if (!isRunning()) return
        val words = mutableListOf<Word>()
        for (block in latin.textBlocks) for (line in block.lines) for (element in line.elements) {
            val rank = rankOf(element.text) ?: continue
            val bounds = element.boundingBox ?: continue
            words.add(Word(rank, bounds))
        }
        val board = words.filter { word ->
            val r = word.bounds
            r.centerX() in (width * .20).toInt()..(width * .82).toInt() &&
                r.centerY() in (height * .12).toInt()..(height * .76).toInt() && r.height() <= height * .06
        }.sortedBy { it.bounds.height() }.fold(mutableListOf<Word>()) { kept, next ->
            val duplicate = kept.any { old -> old.rank == next.rank &&
                abs(old.bounds.centerX() - next.bounds.centerX()) < width * .045 &&
                abs(old.bounds.centerY() - next.bounds.centerY()) < height * .10 }
            if (!duplicate) kept.add(next)
            kept
        }
        val waste = words.filter { word ->
            val r = word.bounds
            r.centerX() in (width * .50).toInt()..(width * .66).toInt() &&
                r.centerY() in (height * .77).toInt()..(height * .98).toInt() && r.height() <= height * .08
        }.minByOrNull { abs(it.bounds.centerX() - width * .56) + abs(it.bounds.centerY() - height * .87) }

        if (board.size >= 2 && waste != null && activePackage != null) boundGamePackage = activePackage
        if (boundGamePackage == null || activePackage != boundGamePackage) return

        val greenButtons = findGreenButtons(bitmap)
        val close = findRedClose(bitmap)
        val combinedText = latin.text + " " + korean
        val isLevelDialog = korean.contains("레벨") || korean.contains("플레이")
        val isShop = korean.contains("구매") || korean.contains("상점") || korean.contains("패키지") || korean.contains("스타터")
        val hasCashPrice = combinedText.contains("₩") || korean.contains("원") ||
            Regex("(?:W|\\$)\\s?\\d{2,}|\\d{2,}\\s?(?:won|원)", RegexOption.IGNORE_CASE).containsMatchIn(latin.text)

        if (close != null && (hasCashPrice || isShop || (greenButtons.size >= 2 && !isLevelDialog))) {
            tap(close.first, close.second)
            return
        }

        val levelButton = greenButtons.singleOrNull { it.width() > width * .12 && it.height() > height * .065 }
        if (levelButton != null && close != null && isLevelDialog) {
            tap(levelButton.centerX(), levelButton.centerY())
            return
        }

        // After selecting the next level, the game may show a separate Start button.
        val startButton = greenButtons.singleOrNull {
            it.width() > width * .12 && it.height() > height * .065 &&
                it.centerX() in (width * .34).toInt()..(width * .68).toInt()
        }
        if (waste == null && startButton != null && close == null) {
            tap(startButton.centerX(), startButton.centerY())
            return
        }

        if (close != null && waste == null && !isLevelDialog) {
            // An unrecognized modal with an X is closed instead of risking a purchase.
            tap(close.first, close.second)
            return
        }

        val current = waste ?: return
        if (board.size < 2) return
        val chosenRank = bestNextRank(board.map { it.rank }, current.rank)
        val chosen = board.filter { it.rank == chosenRank }.maxByOrNull { it.bounds.centerY() }
        if (chosen != null) {
            val targetX = chosen.bounds.left + width * .034f
            val targetY = chosen.bounds.top + height * .085f
            tap(targetX.toInt(), targetY.toInt())
        } else {
            tap((width * .44f).toInt(), (height * .89f).toInt())
        }
    }

    private fun rankOf(raw: String): Int? = when (raw.trim().uppercase(Locale.ROOT)) {
        "A" -> 1; "J" -> 11; "Q" -> 12; "K" -> 13
        else -> raw.trim().toIntOrNull()?.takeIf { it in 2..10 }
    }

    /** Searches all sequences in the currently visible rank pool and maximizes cards removed before drawing. */
    private fun bestNextRank(faceUp: List<Int>, wasteRank: Int): Int {
        val counts = IntArray(14)
        faceUp.forEach { if (it in 1..13) counts[it]++ }
        val memo = HashMap<Long, Plan>()
        fun key(current: Int): Long {
            var code = current.toLong()
            for (rank in 1..13) code = code or (counts[rank].toLong() shl (4 + (rank - 1) * 3))
            return code
        }
        fun solve(current: Int): Plan {
            val state = key(current)
            memo[state]?.let { return it }
            var best = Plan(0, -1)
            val options = listOf(if (current == 1) 13 else current - 1, if (current == 13) 1 else current + 1)
            for (rank in options) {
                if (counts[rank] == 0) continue
                counts[rank]--
                val length = 1 + solve(rank).length
                counts[rank]++
                if (length > best.length || (length == best.length && (best.firstRank < 0 || rank < best.firstRank))) {
                    best = Plan(length, rank)
                }
            }
            memo[state] = best
            return best
        }
        return solve(wasteRank).firstRank
    }

    private fun tap(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < 750) return
        lastActionAt = now
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun findGreenButtons(bitmap: Bitmap): List<Rect> = colorComponents(bitmap) { color ->
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        g > 145 && g > r * 1.22 && g > b * 1.08
    }.filter { rect ->
        rect.width() > bitmap.width * .065 && rect.height() > bitmap.height * .035 &&
            rect.centerY() in (bitmap.height * .42).toInt()..(bitmap.height * .80).toInt()
    }

    private fun findRedClose(bitmap: Bitmap): Pair<Int, Int>? {
        val candidates = colorComponents(bitmap) { color ->
            val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
            r > 155 && g < 115 && b < 125 && r > g * 1.45
        }.filter { rect ->
            val ratio = rect.width().toFloat() / rect.height().coerceAtLeast(1)
            rect.width() >= bitmap.width * .022 && rect.height() >= bitmap.height * .035 &&
                ratio in .65f..1.45f && rect.centerX() in (bitmap.width * .25).toInt()..(bitmap.width * .85).toInt() &&
                rect.centerY() in (bitmap.height * .05).toInt()..(bitmap.height * .70).toInt()
        }
        val button = candidates.maxByOrNull { it.width() * it.height() } ?: return null
        return button.centerX() to button.centerY()
    }

    private fun colorComponents(bitmap: Bitmap, matches: (Int) -> Boolean): List<Rect> {
        val step = 3
        val columns = bitmap.width / step
        val rows = bitmap.height / step
        if (columns <= 0 || rows <= 0) return emptyList()
        val visited = BooleanArray(columns * rows)
        val queue = ArrayDeque<Int>()
        val components = ArrayList<Rect>()
        for (sy in 0 until rows) for (sx in 0 until columns) {
            val start = sy * columns + sx
            if (visited[start] || !matches(bitmap.getPixel(sx * step, sy * step))) continue
            visited[start] = true; queue.addLast(start)
            var left = sx; var right = sx; var top = sy; var bottom = sy; var size = 0
            while (queue.isNotEmpty()) {
                val point = queue.removeFirst(); val x = point % columns; val y = point / columns; size++
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until columns || ny !in 0 until rows) continue
                    val index = ny * columns + nx
                    if (!visited[index] && matches(bitmap.getPixel(nx * step, ny * step))) {
                        visited[index] = true; queue.addLast(index)
                    }
                }
            }
            if (size > 80) components.add(Rect(left * step, top * step, (right + 1) * step, (bottom + 1) * step))
        }
        return components
    }
}
