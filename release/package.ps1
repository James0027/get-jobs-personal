param(
    [Parameter(Mandatory = $true)][string]$Commit,
    [string]$Jar = '',
    [string]$PatchrightDir = '',
    [string]$Sqlite = ''
)

$ErrorActionPreference = 'Stop'
$releaseDir = $PSScriptRoot
$root = Split-Path -Parent $releaseDir
if (-not $Jar) { $Jar = Join-Path $root 'build/libs/get_jobs-2.0.1.jar' }
if (-not $PatchrightDir) { $PatchrightDir = Join-Path $root 'build/patchright-driver' }
if (-not $Sqlite) {
    $cmd = Get-Command sqlite3 -ErrorAction SilentlyContinue
    if ($cmd) { $Sqlite = $cmd.Source }
    elseif (Test-Path 'C:\msys64\ucrt64\bin\sqlite3.exe') { $Sqlite = 'C:\msys64\ucrt64\bin\sqlite3.exe' }
}
if (-not (Test-Path $Jar)) { throw "JAR not found: $Jar (run gradlew bootJar first)" }
if (-not (Test-Path (Join-Path $PatchrightDir 'package/cli.js'))) {
    throw "Patchright driver not found: $PatchrightDir (run gradlew installPatchrightDriver or pass -PatchrightDir)"
}
if (-not $Sqlite -or -not (Test-Path $Sqlite)) { throw 'sqlite3 was not found; pass -Sqlite <path>' }

$stageRoot = Join-Path $root 'build/package'
$stage = Join-Path $stageRoot 'getjob-2.0.1-win64'
$zip = Join-Path $stageRoot 'getjob-2.0.1-win64.zip'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
if (Test-Path $zip) { Remove-Item $zip -Force }
New-Item -ItemType Directory -Force (Join-Path $stage 'db'), (Join-Path $stage 'browser-data') | Out-Null

Copy-Item $Jar (Join-Path $stage 'get_jobs-2.0.1.jar')
Copy-Item $PatchrightDir (Join-Path $stage 'patchright-driver') -Recurse
Copy-Item (Join-Path $releaseDir 'start.cmd') (Join-Path $stage '启动.cmd')
Copy-Item (Join-Path $releaseDir 'stop.cmd') (Join-Path $stage '停止.cmd')
Copy-Item (Join-Path $releaseDir 'check-port.ps1') (Join-Path $stage 'check-port.ps1')
Copy-Item (Join-Path $releaseDir 'find-jdk.ps1') (Join-Path $stage 'find-jdk.ps1')

$dbPath = Join-Path $stage 'db/getjobs.db'
& $Sqlite $dbPath ".read $releaseDir\db-template.sql"
if ($LASTEXITCODE -ne 0) { throw 'Failed to create schema-only db template' }
$rows = (& $Sqlite $dbPath "SELECT (SELECT COUNT(*) FROM config)+(SELECT COUNT(*) FROM boss_config)+(SELECT COUNT(*) FROM cookie)+(SELECT COUNT(*) FROM ai);").Trim()
$tables = (& $Sqlite $dbPath "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';").Trim()
if ($rows -ne '0') { throw "Template db must have 0 personal/default rows, found: $rows" }
if ([int]$tables -lt 20) { throw "Template db has too few tables: $tables" }

$jarSha = (Get-FileHash (Join-Path $stage 'get_jobs-2.0.1.jar') -Algorithm SHA256).Hash
$readme = Get-Content (Join-Path $releaseDir 'README-启动说明.txt.template') -Raw
$readme = $readme.Replace('__COMMIT__', $Commit).Replace('__JAR_SHA256__', $jarSha)
$readme = $readme -replace "`r?`n", "`r`n"
[IO.File]::WriteAllText((Join-Path $stage 'README-启动说明.txt'), $readme, [Text.UTF8Encoding]::new($false))

foreach ($cmdFile in @('启动.cmd', '停止.cmd')) {
    $path = Join-Path $stage $cmdFile
    $text = ([IO.File]::ReadAllText($path)) -replace "`r?`n", "`r`n"
    [IO.File]::WriteAllText($path, $text, [Text.Encoding]::ASCII)
}

# Refuse to ship runtime state or common secret files.
$forbidden = Get-ChildItem $stage -Recurse -Force | Where-Object {
    $_.FullName -match '(?i)\\(target|out|browser-data\\.+|cookies?|resume)\\' -or
    $_.Name -match '(?i)^(cookie\.json|\.env|.*\.log|getjobs\.db-wal|getjobs\.db-shm)$'
}
if ($forbidden) { throw ("Forbidden package files: " + (($forbidden | ForEach-Object FullName) -join ', ')) }

Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal -Force
if (-not (Test-Path $zip)) { throw 'Zip creation failed' }

$zipSha = (Get-FileHash $zip -Algorithm SHA256).Hash
$lines = @(
    "$zipSha  getjob-2.0.1-win64.zip",
    "$jarSha  get_jobs-2.0.1.jar",
    "$((Get-FileHash (Join-Path $stage 'db/getjobs.db') -Algorithm SHA256).Hash)  db/getjobs.db",
    "$((Get-FileHash (Join-Path $stage '启动.cmd') -Algorithm SHA256).Hash)  启动.cmd",
    "$((Get-FileHash (Join-Path $stage '停止.cmd') -Algorithm SHA256).Hash)  停止.cmd",
    "$((Get-FileHash (Join-Path $stage 'check-port.ps1') -Algorithm SHA256).Hash)  check-port.ps1",
    "$((Get-FileHash (Join-Path $stage 'find-jdk.ps1') -Algorithm SHA256).Hash)  find-jdk.ps1",
    "$((Get-FileHash (Join-Path $stage 'README-启动说明.txt') -Algorithm SHA256).Hash)  README-启动说明.txt"
)
[IO.File]::WriteAllText((Join-Path $stageRoot 'SHA256SUMS.txt'), (($lines -join "`r`n") + "`r`n"), [Text.UTF8Encoding]::new($false))
Write-Output ("ZIP: $zip")
Write-Output ("SHA256: $zipSha")
Write-Output ("JAR SHA256: $jarSha")
Write-Output ("DB tables=$tables rows=$rows")
