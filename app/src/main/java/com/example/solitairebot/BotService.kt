package com.example.solitairebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * 디즈니 솔리테어(트라이픽스 방식) 자동 플레이.
 * 규칙: 더미 옆 카드(waste)와 숫자가 1 차이 나는 보드 카드를 탭 → 없으면 더미(stock)를 탭.
 */
class BotService : AccessibilityService() {

    private data class Card(val rank: Int, val cx: Float, val cy: Float, val bright: Int, val h: Int)

    // ---- 튜닝용 상수 (화면 비율) ----
    private val allowWrap = false          // K-A 연결 여부
    private val stockX = 0.468f            // 더미 위치
    private val stockY = 0.880f
    private val wasteXRange = 0.52f..0.64f // 놓인 카드 위치
    private val wasteMinY = 0.78f
    private val boardMinY = 0.10f
    private val minGlyphH = 0.05f          // 큰 숫자 최소 높이(화면 높이 대비)
    private val maxGlyphH = 0.22f
    private val brightTolerance = 10       // 가장 밝은 카드 대비 허용 오차(가려진 카드 제외용)
    private val stepDelayMs = 1100L

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var running = false
    private var button: Button? = null
    private var stockStreak = 0
    private var lastWasteRank = -1
    private var nullStreak = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "▶ 시작"
            setOnClickListener { toggle() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 8
        }
        wm.addView(b, lp)
        button = b
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stop(null) }

    override fun onDestroy() {
        stop(null)
        button?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        super.onDestroy()
    }

    private fun toggle() {
        if (running) { stop("정지했습니다"); return }
        running = true
        stockStreak = 0; lastWasteRank = -1; nullStreak = 0
        button?.text = "■ 정지"
        handler.removeCallbacksAndMessages(null)
        schedule(300)
    }

    private fun stop(msg: String?) {
        running = false
        handler.removeCallbacksAndMessages(null)
        button?.text = "▶ 시작"
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun schedule(ms: Long) {
        handler.postDelayed({ step() }, ms)
    }

    // ---- 1) 스크린샷 ----
    private fun step() {
        if (!running) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                r.hardwareBuffer.close()
                if (bmp == null) schedule(1500) else analyze(bmp)
            }
            override fun onFailure(errorCode: Int) { schedule(1500) }
        })
    }

    // ---- 2) 숫자 인식(OCR) ----
    private fun analyze(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t -> decide(bmp, t) }
            .addOnFailureListener { schedule(1500) }
    }

    private fun rankOf(raw: String): Int {
        val s = raw.trim().uppercase()
        return when (s) {
            "A" -> 1; "J" -> 11; "Q" -> 12; "K" -> 13
            else -> s.toIntOrNull()?.takeIf { it in 2..10 } ?: 0
        }
    }

    private fun bgLum(b: Bitmap, r: Rect): Int {
        val x = r.left.coerceIn(0, b.width - 1)
        val y = r.top.coerceIn(0, b.height - 1)
        val w = (r.right.coerceIn(x + 1, b.width)) - x
        val h = (r.bottom.coerceIn(y + 1, b.height)) - y
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, x, y, w, h)
        var sum = 0L; var n = 0
        for (p in px) {
            val l = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            if (l > 150) { sum += l; n++ }
        }
        return if (n > 0) (sum / n).toInt() else 0
    }

    private fun adj(a: Int, b: Int) =
        abs(a - b) == 1 || (allowWrap && ((a == 1 && b == 13) || (a == 13 && b == 1)))

    private fun chain(cur: Int, pool: List<Card>, used: BooleanArray): Int {
        var best = 0
        for (i in pool.indices) {
            if (!used[i] && adj(cur, pool[i].rank)) {
                used[i] = true
                best = maxOf(best, 1 + chain(pool[i].rank, pool, used))
                used[i] = false
            }
        }
        return best
    }

    // ---- 3) 판단 + 탭 ----
    private fun decide(bmp: Bitmap, text: Text) {
        if (!running) return
        val W = bmp.width; val H = bmp.height
        val board = mutableListOf<Card>()
        var waste: Card? = null

        for (blk in text.textBlocks) for (ln in blk.lines) for (el in ln.elements) {
            val box = el.boundingBox ?: continue
            val r = rankOf(el.text)
            if (r == 0) continue
            if (box.height() < H * minGlyphH || box.height() > H * maxGlyphH) continue
            val c = Card(r, box.exactCenterX(), box.exactCenterY(), bgLum(bmp, box), box.height())
            if (c.cy > H * wasteMinY) {
                if (c.cx in (W * wasteXRange.start)..(W * wasteXRange.endInclusive) &&
                    (waste == null || c.h > waste.h)) waste = c
            } else if (c.cy > H * boardMinY) {
                board.add(c)
            }
        }

        val w = waste
        if (w == null) {
            if (++nullStreak >= 4) { stop("카드 인식 실패 - 정지"); return }
            tap(W * stockX, H * stockY) { schedule(stepDelayMs) }
            return
        }
        nullStreak = 0

        val maxB = board.maxOfOrNull { it.bright } ?: 0
        val playable = board.filter { it.bright >= maxB - brightTolerance }.take(12)
        val used = BooleanArray(playable.size)
        var bestIdx = -1; var bestLen = 0
        for (i in playable.indices) {
            if (adj(w.rank, playable[i].rank)) {
                used[i] = true
                val len = 1 + chain(playable[i].rank, playable, used)
                used[i] = false
                if (len > bestLen) { bestLen = len; bestIdx = i }
            }
        }

        if (bestIdx >= 0) {
            stockStreak = 0; lastWasteRank = -1
            val c = playable[bestIdx]
            tap(c.cx, c.cy) { schedule(stepDelayMs) }
        } else {
            stockStreak = if (w.rank == lastWasteRank) stockStreak + 1 else 1
            lastWasteRank = w.rank
            if (stockStreak >= 4) { stop("더 이상 진행할 수 없어 정지했습니다"); return }
            tap(W * stockX, H * stockY) { schedule(stepDelayMs) }
        }
    }

    private fun tap(x: Float, y: Float, done: () -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) { done() }
            override fun onCancelled(gd: GestureDescription?) { done() }
        }, null)
    }
}
