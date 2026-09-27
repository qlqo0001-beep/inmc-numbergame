@echo off
chcp 65001 >nul
setlocal

rem inmc-numbergame 을 빌드 캐시·증분 결과 없이 새로 빌드하고,
rem 결과 jar 에 메인 클래스(NumberGamePlugin)가 들어갔는지 확인한다.
rem 이 파일은 inmc-numbergame 폴더 안에 두고 실행한다. gradlew.bat 은 한 단계 위 루트 프로젝트에 있다.

cd /d "%~dp0.."
if not exist gradlew.bat (
    echo [오류] %CD% 에 gradlew.bat 이 없습니다. 이 파일을 inmc-numbergame 폴더 안에 두세요.
    pause
    exit /b 1
)

call gradlew.bat :inmc-numbergame:clean :inmc-numbergame:shadowJar --no-build-cache --rerun-tasks
if errorlevel 1 (
    echo [실패] 빌드가 실패했습니다. 위 로그를 확인하세요.
    pause
    exit /b 1
)

set "JAR=inmc-numbergame\build\libs\inmc-numbergame-1.0.0.jar"
set "CLS=inmc-numbergame\build\classes\kotlin\main\com\inmc\numbergame\NumberGamePlugin.class"

tar -tf "%JAR%" | findstr /c:"com/inmc/numbergame/NumberGamePlugin.class" >nul
if errorlevel 1 (
    echo [실패] %JAR% 에 NumberGamePlugin.class 가 없습니다.
    if exist "%CLS%" (
        echo  - build\classes 에는 있습니다: shadowJar 설정이 이 클래스를 빼고 있습니다.
    ) else (
        echo  - build\classes 에도 없습니다: src\main\kotlin\com\inmc\numbergame\NumberGamePlugin.kt 파일을 확인하세요.
    )
    pause
    exit /b 1
)

echo [성공] %CD%\%JAR%
echo NumberGamePlugin.class 포함 확인. 이 jar 을 plugins 폴더에 넣으면 됩니다.
pause
