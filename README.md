# APK-builder

HTML 단일 파일을 안드로이드 앱(APK)으로 감싸 GitHub Actions에서 자동 빌드합니다.

## 지금 들어있는 앱
- **말랑한 미소 · Interaction Lab** (`app/src/main/assets/index.html`)

## APK 받기
1. 이 저장소의 **Releases**(오른쪽 또는 아래쪽)에서 가장 최신 항목을 엽니다.
2. **SoftSmile.apk**를 눌러 다운로드 → 설치합니다.
3. 처음 한 번은 브라우저(Chrome/삼성 인터넷)에 **출처를 알 수 없는 앱 설치**를 허용해야 합니다.

## 다른 HTML로 바꾸려면
`app/src/main/assets/index.html` 파일만 교체해서 올리면 몇 분 뒤 새 APK가 Releases에 생깁니다.
(앱 이름은 `app/src/main/AndroidManifest.xml`의 `android:label`에서 바꿉니다.)

## 앱 동작
- 화면 전체 WebView, 메모(localStorage) 저장 유지
- 웹 진동(`navigator.vibrate`)을 기기 진동으로 연결
- 폴드를 접고 펴도 화면 상태가 유지됨
