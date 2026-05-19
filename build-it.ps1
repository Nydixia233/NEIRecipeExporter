$ErrorActionPreference = 'Continue'
$root = 'E:\Minecraft Mod\nei-recipe-exporter'
$env:JAVA_HOME = Join-Path $root 'tools\jdk21'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
Set-Location $root
& "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Tee-Object -FilePath build.log
& (Join-Path $root 'tools\gradle-8.10\bin\gradle.bat') --no-daemon --console=plain --stacktrace build 2>&1 | Tee-Object -FilePath build.log -Append
"EXIT=$LASTEXITCODE" | Tee-Object -FilePath build.log -Append
