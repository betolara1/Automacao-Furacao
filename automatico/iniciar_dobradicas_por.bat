@echo off
REM Inicia o monitoramento da pasta POR (precisa do Java 21 no PATH).
REM O log fica nesta mesma pasta: dobradicas_automaticas_por.log
cd /d "%~dp0"
java dobradicas_automaticas_por.java
