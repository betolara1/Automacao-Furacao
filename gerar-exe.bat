@echo off
REM Gera o executavel Windows com o Java embutido (nao precisa de Java instalado no PC que vai usar).
REM Resultado: build-exe\AutomatizarDobradicas\AutomatizarDobradicas.exe
REM Precisa do JDK 21 no PATH (o jpackage vem junto com o JDK).
cd /d "%~dp0"

call compilar.bat semPausa
if errorlevel 1 (
    pause
    exit /b 1
)

if exist build-exe rmdir /s /q build-exe
mkdir build-exe\input
copy /y automatizar-dobradicas.jar build-exe\input\ >nul

jpackage ^
  --type app-image ^
  --name AutomatizarDobradicas ^
  --app-version 1.0.0 ^
  --input build-exe\input ^
  --main-jar automatizar-dobradicas.jar ^
  --main-class dobradicas.App ^
  --add-modules java.base,java.desktop,java.xml,java.prefs ^
  --dest build-exe

if errorlevel 1 (
    echo.
    echo ERRO ao gerar o executavel.
    pause
    exit /b 1
)

rmdir /s /q build-exe\input
echo.
echo OK - build-exe\AutomatizarDobradicas\AutomatizarDobradicas.exe
echo Para levar para outro PC, copie a pasta build-exe\AutomatizarDobradicas inteira.
pause
