param(
    [int]$Port = 8888
)

$ErrorActionPreference = 'SilentlyContinue'

$client = New-Object System.Net.Sockets.TcpClient
try {
    $client.Connect('127.0.0.1', $Port)
    $occupied = $true
} catch {
    $occupied = $false
} finally {
    $client.Close()
}

if (-not $occupied) {
    Write-Output "free"
    exit 10
}

try {
    $response = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/api/login-only/status" -TimeoutSec 3
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -eq 200 -and $json.tasksStarted -eq $false -and $json.schedulerEnabled -eq $false) {
        Write-Output "get-jobs"
        exit 0
    }
    Write-Output ("unknown-service status=" + $response.StatusCode + " body=" + $response.Content)
    exit 11
} catch {
    Write-Output ("unknown-service error=" + $_.Exception.Message)
    exit 12
}
