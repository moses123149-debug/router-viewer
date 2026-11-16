@echo off
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /codepage:65001 /target:winexe /win32manifest:app.manifest /win32icon:icon.ico /out:RouterViewerPC.exe RouterViewerPC.cs
