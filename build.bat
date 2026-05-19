@echo off
set JAVA_HOME=E:\Minecraft Mod\gtnh mod\nei-recipe-exporter\tools\jdk21
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d "E:\Minecraft Mod\gtnh mod\nei-recipe-exporter"
tools\gradle-8.10\bin\gradle.bat --no-daemon --console=plain assemble -x clean
echo EXIT=%ERRORLEVEL%
