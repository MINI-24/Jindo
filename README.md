# Odin Launcher (Cocoon Ultra Customizer)

AYN Odin 3 전용 커스텀 게임 런처입니다.

## 기능 특징
- **고성능 리퀴드 글래스(Liquid Glass) UI**: 1080p/120Hz 지원
- **플랫폼별 롬 관리**: Switch, 3DS, PS Vita, PC Gaming, Android Native
- **Cocoon 스타일 하단 독(Bottom Dock)**: 6슬롯 완벽 커스텀, 자동 숨김 옵션
- **SteamGridDB 연동**: 박스아트, 로고, 히어로 배너 비주얼 피커
- **포커스 & 테마 커스텀**: 4모서리 강조, 네온 글로우, 윈도우 스타일 액센트 컬러 피커
- **내장 OTA 업데이트 엔진**: GitHub 릴리즈 자동 감지 및 원클릭 업데이트

## 빌드 방법
```bash
# 의존성 설치
npm install

# 웹 빌드 동기화
npm run build
npx cap sync android

# APK 빌드 (또는 build_apk.bat 실행)
cd android
./gradlew assembleDebug
```
빌드된 APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
