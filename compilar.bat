@echo off
REM Compila o projeto e gera automatizar-dobradicas.jar (precisa do JDK 21 no PATH)
REM Uso: compilar.bat            -> pausa no final
REM      compilar.bat semPausa   -> usado pelo gerar-exe.bat
cd /d "%~dp0"

if exist out rmdir /s /q out
mkdir out

pushd src\main\java
javac -encoding UTF-8 -d ..\..\..\out dobradicas\App.java dobradicas\model\*.java dobradicas\service\*.java dobradicas\ui\*.java
if errorlevel 1 (
    popd
    echo.
    echo ERRO na compilacao.
    if not "%1"=="semPausa" pause
    exit /b 1
)
popd

jar --create --file automatizar-dobradicas.jar --main-class dobradicas.App -C out .
if errorlevel 1 (
    echo ERRO ao gerar o jar.
    if not "%1"=="semPausa" pause
    exit /b 1
)

echo.
echo OK - automatizar-dobradicas.jar gerado.
if not "%1"=="semPausa" pause
exit /b 0
