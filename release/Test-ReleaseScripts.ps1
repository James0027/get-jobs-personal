param()

$ErrorActionPreference = 'Stop'
$releaseDir = $PSScriptRoot
$failures = New-Object System.Collections.Generic.List[string]

function Assert-True([bool]$condition, [string]$message) {
    if ($condition) { Write-Output ("PASS: " + $message) }
    else { $failures.Add($message); Write-Output ("FAIL: " + $message) }
}

$startText = Get-Content (Join-Path $releaseDir 'start.cmd') -Raw
$stopText = Get-Content (Join-Path $releaseDir 'stop.cmd') -Raw
Assert-True ($startText -match 'check-port\.ps1') 'start.cmd checks port/identity before launching'
Assert-True ($startText -match '-Dserver\.address=127\.0\.0\.1') 'start.cmd binds the main port to loopback'
Assert-True ($stopText -notmatch '(?i)Stop-Process|taskkill') 'stop.cmd never kills processes'

$freePort = 18899
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port $freePort | Out-Null
Assert-True ($LASTEXITCODE -eq 10) "check-port reports free port $freePort as free"

$dummy = Start-Job -ScriptBlock {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 8888)
    $listener.Start()
    try {
        while ($true) {
            if ($listener.Pending()) {
                $client = $listener.AcceptTcpClient()
                $stream = $client.GetStream()
                $buffer = New-Object byte[] 8192
                [void]$stream.Read($buffer, 0, $buffer.Length)
                $body = 'not get jobs'
                $response = "HTTP/1.1 200 OK`r`nContent-Type: text/plain`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n$body"
                $bytes = [Text.Encoding]::ASCII.GetBytes($response)
                $stream.Write($bytes, 0, $bytes.Length)
                $stream.Close()
                $client.Close()
            } else {
                Start-Sleep -Milliseconds 50
            }
        }
    } finally {
        $listener.Stop()
    }
}
Start-Sleep -Milliseconds 800

try {
    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port 8888 | Out-Null
    $checkCode = $LASTEXITCODE
    Assert-True ($checkCode -ne 10 -and $checkCode -ne 0) 'check-port does not accept a foreign service on 8888'

    cmd /c "release\start.cmd --yes"
    $startCode = $LASTEXITCODE
    Assert-True ($startCode -eq 1) 'start.cmd refuses to start when another service owns 8888'

    cmd /c "release\stop.cmd --yes" | Out-Null
    $stopCode = $LASTEXITCODE
    Assert-True ($stopCode -eq 0) 'stop.cmd exits without action'
    Assert-True ((Get-Job -Id $dummy.Id).State -eq 'Running') 'stop.cmd leaves the foreign service running'
} finally {
    Stop-Job $dummy -ErrorAction SilentlyContinue
    Remove-Job $dummy -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    Write-Output ("RELEASE SCRIPT TESTS FAILED: " + $failures.Count)
    exit 1
}
Write-Output 'RELEASE SCRIPT TESTS PASSED'
exit 0
