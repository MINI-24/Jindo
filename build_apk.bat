@echo off
echo ============================================
echo   Building Jindo Launcher APK...
echo ============================================

cd /d "%~dp0"
call npx cap sync android

cd android
call gradlew.bat assembleDebug

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "C:\Users\V\Desktop\JindoLauncher.apk"
    echo.
    echo ============================================
    echo   [SUCCESS] APK Created on Desktop:
    echo   C:\Users\V\Desktop\JindoLauncher.apk
    echo ============================================
) else (
    echo.
    echo [ERROR] Build failed. Check gradle logs above.
)
pause
