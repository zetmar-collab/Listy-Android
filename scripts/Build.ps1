param([switch]$ConnectedTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$arguments = @('-p', $projectRoot, '--no-daemon', '--max-workers', '2', 'testDebugUnitTest', 'lintDebug', 'assembleDebug')
if ($ConnectedTests) { $arguments += 'connectedDebugAndroidTest' }
& (Join-Path $projectRoot 'gradlew.bat') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Android build or tests failed.' }
$output = Join-Path $projectRoot 'dist\1.0.0'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$apk = Join-Path $output 'Listy-Android-1.0.0.apk'
Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apk -Force
$sum = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() + '  Listy-Android-1.0.0.apk' + [Environment]::NewLine
[IO.File]::WriteAllText((Join-Path $output 'SHA256SUMS.txt'), $sum, [Text.UTF8Encoding]::new($false))
Write-Host "APK: $apk"
