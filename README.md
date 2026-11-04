# APK-builder

단일 파일 HTML 도구를 안드로이드 앱(APK)으로 감싸 GitHub Actions에서 자동 빌드합니다.
앱마다 따로 설치되며(서로 다른 앱 ID), 코드를 올리면 몇 분 뒤 **Releases**에 새 APK가 생깁니다.

## 들어있는 앱
| APK | 앱 이름 | HTML 위치 |
|---|---|---|
| SoftSmile.apk | 말랑한 미소 | `app/src/softsmile/assets/index.html` |
| CMFCube.apk | 도약 CMF 큐브 | `app/src/cmfcube/assets/index.html` (three.js 0.160.0 내장 → 오프라인 동작) |
| HandFlowArt.apk | Hand Flow Art | 웹사이트 `kyuil71.github.io/Camera-Hand-/hand-art/`를 그대로 열어 항상 최신 (카메라 손 인식) |

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

### Hand Flow Art 전용
- **화면 회전 완전 고정**: 안드로이드 16의 큰 화면(폴드 내부 화면)은 앱의 방향 고정 요청을 무시할 수 있어서,
  처음 켤 때 '시스템 설정 변경'을 허용하면 앱이 보이는 동안만 기기의 자동 회전을 끄고 지금 방향으로 고정합니다.
  앱을 나가면 원래 자동 회전 설정으로 되돌립니다. (targetSdk 34라 방향 잠금 요청도 함께 적용)
- 카메라 권한(손 인식), 전체 화면(상태바·내비게이션 바 숨김), 화면 꺼짐 방지
- 녹화(MP4)·캡처(JPEG)는 갤러리의 `Movies/Hand Flow Art`, `Pictures/Hand Flow Art`에 저장

## 네이티브 앱: AI Road Guardian (`road-guardian/`)
Kotlin · CameraX · YOLOv8n(ONNX) 기반 주행 위험 인식 시험용 앱. 별도 워크플로가 빌드해서
Releases에 **RoadGuardian.apk**로 올립니다. 자세한 내용은 [`road-guardian/README.md`](road-guardian/README.md).
