# 솔리테어 자동화 (Android)

## 폰에서 APK 받는 법 (GitHub 이용, 폰만으로 가능)
1. github.com 에 로그인 → New repository (예: SolitaireBot)
2. 이 zip의 파일 전체를 올리기 (Add file > Upload files, 폴더 구조 유지 / `.github` 폴더 포함)
3. 상단 Actions 탭 → "Build APK" 실행 완료까지 약 3~5분 대기
4. 저장소 오른쪽 Releases → latest → SolitaireBot.apk 다운로드 → 설치
   (출처를 알 수 없는 앱 설치 허용 필요)

## 사용
앱 실행 → 접근성 설정에서 서비스 켜기 → 게임을 가로 화면 플레이 상태로 → 상단 중앙 '▶ 시작'.

## 튜닝 (BotService.kt 상단 상수)
stockX/stockY(더미 위치), wasteXRange(놓인 카드 위치), allowWrap(K-A 연결), brightTolerance(가려진 카드 판별)
