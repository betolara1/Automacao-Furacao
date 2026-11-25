@echo off
REM Passa UMA VEZ em todos os .scx da pasta POR e refaz as dobradicas (precisa do Java 21 no PATH).
REM O log fica nesta mesma pasta: dobradicas_reprocessar_por.log
cd /d "%~dp0"
java dobradicas_reprocessar_por.java
pause
