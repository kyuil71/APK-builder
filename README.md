# APK-builder

단일 파일 HTML 도구를 안드로이드 앱(APK)으로 감싸 GitHub Actions에서 자동 빌드합니다.
앱마다 따로 설치되며(서로 다른 앱 ID), 코드를 올리면 몇 분 뒤 **Releases**에 새 APK가 생깁니다.

## 들어있는 앱
| APK | 앱 이름 | HTML 위치 |
|---|---|---|
| SoftSmile.apk | 말랑한 미소 | `app/src/softsmile/assets/index.html` |
| CMFCube.apk | 도약 CMF 큐브 | `app/src/cmfcube/assets/index.html` (three.js 0.160.0 내장 → 오프라인 동작) |

## APK 받기
1. 이 저장소의 **Releases**에서 가장 최신 항목을 엽니다.
2. 원하는 APK를 눌러 다운로드 → 설치합니다.
3. 처음 한 번은 브라우저에 **출처를 알 수 없는 앱 설치**를 허용해야 합니다.

## 새 HTML 앱 추가하기
1. `app/src/<이름>/assets/index.html` 에 HTML을 넣습니다.
2. `app/build.gradle`의 `productFlavors`에 블록을 하나 추가합니다 (앱 ID·이름·배경색).
3. `app/src/<이름>/res/values/themes.xml`, `app/src/<이름>/res/drawable/ic_app.xml`(아이콘)을 만듭니다.
4. `.github/workflows/build-apk.yml`의 **Collect APKs**에 복사 줄을 추가합니다.

## 앱 동작
- HTML을 `https://appassets.androidplatform.net/assets/` 주소로 열어 ES 모듈·웹폰트·localStorage가 웹사이트처럼 동작
- 웹 진동(`navigator.vibrate`)을 기기 진동으로 연결
- 폴드를 접고 펴도 화면 상태가 유지됨
