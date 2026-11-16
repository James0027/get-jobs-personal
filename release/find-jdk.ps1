function Test-Jdk21([string]$jdkHome) {
    $java = Join-Path $jdkHome 'bin\java.exe'
    if (-not (Test-Path $java)) { return $false }
    $first = & $java -version 2>&1 | Select-Object -First 1
    return ($first -match '21\.')
}

$homes = @(
    $env:USERPROFILE,
    ($env:HOMEDRIVE + $env:HOMEPATH),
    [Environment]::GetFolderPath('UserProfile')
) | Where-Object { $_ -and $_.Trim() } | Select-Object -Unique

$candidates = New-Object System.Collections.Generic.List[string]
if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
foreach ($baseHome in $homes) {
    foreach ($root in @((Join-Path $baseHome '.jdks'), (Join-Path $baseHome '.gradle\jdks'))) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Force -Directory $root -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'jdk-21*' } |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
}
foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
    if (-not $base -or -not (Test-Path $base)) { continue }
    Get-ChildItem -Force -Directory $base -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-21*' -or $_.Name -like '*jdk*21*' } |
        ForEach-Object { $candidates.Add($_.FullName) }
    foreach ($vendor in @('Eclipse Adoptium', 'Microsoft', 'Zulu', 'Amazon Corretto')) {
        $root = Join-Path $base $vendor
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Force -Directory $root -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like '*21*' } |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
}

foreach ($candidate in ($candidates | Select-Object -Unique)) {
    if (Test-Jdk21 $candidate) {
        Write-Output $candidate
        exit 0
    }
}
exit 1
