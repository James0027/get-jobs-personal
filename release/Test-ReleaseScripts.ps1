param()

$ErrorActionPreference = 'Stop'
$releaseDir = $PSScriptRoot
$failures = New-Object System.Collections.Generic.List[string]

function Assert-True([bool]$condition, [string]$message) {
    if ($condition) { Write-Output ("PASS: " + $message) }
    else { $failures.Add($message); Write-Output ("FAIL: " + $message) }
}

function Start-DummyHttp([int]$Port, [string]$Body, [string]$ContentType) {
    return Start-Job -ArgumentList $Port, $Body, $ContentType -ScriptBlock {
        $port = $args[0]; $bodyText = $args[1]; $type = $args[2]
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
        $listener.Start()
        try {
            while ($true) {
                if ($listener.Pending()) {
                    $client = $listener.AcceptTcpClient()
                    $stream = $client.GetStream()
                    $buffer = New-Object byte[] 8192
                    [void]$stream.Read($buffer, 0, $buffer.Length)
                    $response = "HTTP/1.1 200 OK`r`nContent-Type: $type`r`nContent-Length: $($bodyText.Length)`r`nConnection: close`r`n`r`n$bodyText"
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
}

$startText = Get-Content (Join-Path $releaseDir 'start.cmd') -Raw
$stopText = Get-Content (Join-Path $releaseDir 'stop.cmd') -Raw
Assert-True ($startText -match 'check-port\.ps1') 'start.cmd checks port/identity before launching'
Assert-True ($startText -match '-Dserver\.address=127\.0\.0\.1') 'start.cmd binds the main port to loopback'
Assert-True ($stopText -notmatch '(?i)Stop-Process|taskkill') 'stop.cmd never kills processes'

$freePort = 18899
powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port $freePort | Out-Null
Assert-True ($LASTEXITCODE -eq 10) "check-port reports free port $freePort as free"

$dummy = Start-DummyHttp 8888 'not get jobs' 'text/plain'
$shapeOnly = Start-DummyHttp 18897 '{"tasksStarted":false,"schedulerEnabled":false}' 'application/json'
$ours = Start-DummyHttp 18898 '{"platform":"","state":"not_started","initialized":false,"loggedIn":false,"url":"","tasksStarted":false,"schedulerEnabled":false}' 'application/json'
Start-Sleep -Milliseconds 800

try {
    $output = powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port 8888 2>&1 | Out-String
    $checkCode = $LASTEXITCODE
    Assert-True ($checkCode -ne 10 -and $checkCode -ne 0) 'check-port does not accept a foreign service on 8888'
    Assert-True ($output -match 'unknown-service') 'check-port reports a fixed unknown-service summary'
    Assert-True ($output -notmatch 'not get jobs') 'check-port does not echo a foreign response body'

    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port 18897 | Out-Null
    Assert-True ($LASTEXITCODE -ne 0) 'check-port rejects JSON with only the two boolean fields'

    powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $releaseDir 'check-port.ps1') -Port 18898 | Out-Null
    Assert-True ($LASTEXITCODE -eq 0) 'check-port accepts the expected login-only status schema'

    cmd /c "release\start.cmd --yes"
    $startCode = $LASTEXITCODE
    Assert-True ($startCode -eq 1) 'start.cmd refuses to start when another service owns 8888'

    cmd /c "release\stop.cmd --yes" | Out-Null
    $stopCode = $LASTEXITCODE
    Assert-True ($stopCode -eq 0) 'stop.cmd exits without action'
    Assert-True ((Get-Job -Id $dummy.Id).State -eq 'Running') 'stop.cmd leaves the foreign service running'
} finally {
    foreach ($job in @($dummy, $shapeOnly, $ours)) {
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

if ($failures.Count -gt 0) {
    Write-Output ("RELEASE SCRIPT TESTS FAILED: " + $failures.Count)
    exit 1
}
Write-Output 'RELEASE SCRIPT TESTS PASSED'
exit 0
