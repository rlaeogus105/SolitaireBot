package com.example.solitairebot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(TextView(this).apply {
            textSize = 16f
            text = "사용 방법\n\n" +
                "1. 아래 버튼 → 설치된 앱(또는 접근성)에서 '솔리테어 자동화'를 켜세요.\n" +
                "   (켜기가 회색이면: 설정 > 앱 > 솔리테어 자동화 > ⋮ > '제한된 설정 허용')\n" +
                "2. 게임을 가로 화면으로 열고 플레이 화면까지 들어가세요.\n" +
                "3. 화면 위쪽 가운데에 뜨는 '▶ 시작' 버튼을 누르면 자동 플레이가 시작됩니다.\n" +
                "4. 멈추려면 같은 버튼('■ 정지')을 누르세요."
        })
        root.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }
}
