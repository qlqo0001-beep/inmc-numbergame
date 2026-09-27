@echo off
chcp 65001 >nul
setlocal

rem inmc-numbergame 을 빌드 캐시·증분 결과 없이 새로 빌드하고,
rem 결과 jar 에 메인 클래스(NumberGamePlugin)가 들어갔는지 확인한다.
rem 이 파일은 inmc-numbergame 폴더 안에 두고 실행한다. gradlew.bat 은 상위 폴더에서 찾는다.
rem 프로젝트 이름에 기대지 않도록 이 폴더를 -p 로 넘긴다 (Gradle 이 위로 올라가 settings 파일을 찾는다).

set "ROOT=%~dp0"
:findroot
if exist "%ROOT%gradlew.bat" goto foundroot
for %%I in ("%ROOT%..") do set "PARENT=%%~fI\"
if /i "%PARENT%"=="%ROOT%" (
    echo [오류] 상위 폴더 어디에도 gradlew.bat 이 없습니다. 이 파일을 inmc-numbergame 폴더 안에 두세요.
    pause
    exit /b 1
)
set "ROOT=%PARENT%"
goto findroot
:foundroot

call "%ROOT%gradlew.bat" -p "%~dp0." clean shadowJar --no-build-cache --rerun-tasks
if errorlevel 1 (
    echo [실패] 빌드가 실패했습니다. 위 로그를 확인하세요.
    pause
    exit /b 1
)

set "JAR=%~dp0build\libs\inmc-numbergame-1.0.0.jar"
set "CLS=%~dp0build\classes\kotlin\main\com\inmc\numbergame\NumberGamePlugin.class"

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

echo [성공] %JAR%
echo NumberGamePlugin.class 포함 확인. 이 jar 을 plugins 폴더에 넣으면 됩니다.
pause
