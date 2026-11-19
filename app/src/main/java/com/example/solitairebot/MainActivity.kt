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
                "1. 아래 버튼을 눌러 '솔리테어 자동화' 접근성 서비스를 켜세요.\n" +
                "   Android가 제한된 설정 허용을 요구하면 앱 정보에서 허용합니다.\n" +
                "2. 게임을 가로 화면으로 열고 플레이 화면까지 들어갑니다.\n" +
                "3. 화면 위쪽의 '▶ 시작'을 누르면 공개된 카드로 가능한 최선의 연속 경로를 계산해 진행합니다.\n" +
                "4. 레벨이 끝나면 다음 레벨/시작 버튼을 누르고, 현금 구매 창은 X로 닫습니다.\n" +
                "5. 멈출 때는 위쪽 버튼의 '■ 정지'를 누르세요."
        })
        root.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        setContentView(root)
    }
}
