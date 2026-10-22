@echo off
echo ============================================
echo   Building Odin 3 Cocoon Launcher APK...
echo ============================================

cd /d "%~dp0"
copy /Y "C:\Users\V\Desktop\odin_launcher_prototype.html" "dist\index.html"
call npx cap sync android

cd android
call gradlew.bat assembleDebug

if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "C:\Users\V\Desktop\OdinLauncher.apk"
    echo.
    echo ============================================
    echo   [SUCCESS] APK Created on Desktop:
    echo   C:\Users\V\Desktop\OdinLauncher.apk
    echo ============================================
) else (
    echo.
    echo [ERROR] Build failed. Check gradle logs above.
)
pause
