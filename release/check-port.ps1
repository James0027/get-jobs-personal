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
    $required = @('platform', 'state', 'initialized', 'loggedIn', 'url', 'tasksStarted', 'schedulerEnabled')
    $names = @($json.PSObject.Properties.Name)
    $hasSchema = @($required | Where-Object { $names -notcontains $_ }).Count -eq 0
    $platform = [string]$json.platform
    $state = [string]$json.state
    $knownPlatform = @('', 'boss', 'liepin', '51job', 'zhilian') -contains $platform
    $knownState = @('not_started', 'starting', 'running', 'failed') -contains $state
    if ($response.StatusCode -eq 200 -and $hasSchema -and $knownPlatform -and $knownState -and
            $json.tasksStarted -eq $false -and $json.schedulerEnabled -eq $false) {
        Write-Output "get-jobs"
        exit 0
    }
    Write-Output ("unknown-service status=" + $response.StatusCode +
        " content-type=" + $response.Headers['Content-Type'] +
        " length=" + $response.Content.Length)
    exit 11
} catch {
    Write-Output ("unknown-service error=" + $_.Exception.Message)
    exit 12
}
