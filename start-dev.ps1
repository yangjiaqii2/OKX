param(
    [switch]$SkipDocker,
    [switch]$SkipAkShare,
    [switch]$SkipInstall,
    [switch]$CheckOnly,
    [switch]$LocalProfile
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSCommandPath
$FrontendDir = Join-Path $Root "frontend"
$AkShareDir = Join-Path $Root "akshare-service"
$AkShareVenv = Join-Path $Root ".akshare-venv"
$AkSharePython = Join-Path $AkShareVenv "Scripts\python.exe"
$LocalMavenRepo = Join-Path $Root ".m2\repository"
$NpmCache = Join-Path $Root ".npm-cache"

function Resolve-Tool {
    param([string[]]$Names)

    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }

    throw "Missing required command: $($Names -join ' or ')"
}

function New-CmdArguments {
    param(
        [string]$Executable,
        [string]$Arguments
    )

    return "/d /c `"`"$Executable`" $Arguments`""
}

function Start-DevProcess {
    param(
        [string]$Name,
        [string]$Arguments,
        [string]$WorkingDirectory
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "cmd.exe"
    $psi.Arguments = $Arguments
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $false

    $process = [System.Diagnostics.Process]::Start($psi)
    [pscustomobject]@{
        Name = $Name
        Process = $process
    }
}

function Stop-DevProcessTree {
    param([System.Diagnostics.Process]$Process)

    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    & taskkill.exe /PID $Process.Id /T /F | Out-Null
}

if (-not (Test-Path $FrontendDir)) {
    throw "Frontend directory not found: $FrontendDir"
}

$mvn = Resolve-Tool @("mvn.cmd", "mvn")
$npm = Resolve-Tool @("npm.cmd", "npm")
$bundledPython = Join-Path $env:USERPROFILE ".cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe"
$python = if (Test-Path $bundledPython) { $bundledPython } else { Resolve-Tool @("python.exe", "python", "py.exe", "py") }

Write-Host "Project: $Root"
Write-Host "Backend: http://0.0.0.0:8080"
Write-Host "Frontend: http://0.0.0.0:5173"

if (-not $SkipDocker -and (Test-Path (Join-Path $Root "docker-compose.yml"))) {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($docker) {
        Write-Host "Starting docker compose services..."
        & $docker.Source compose up -d
    } else {
        Write-Warning "Docker command not found. Skipping docker compose."
    }
}

if (-not $SkipInstall -and -not (Test-Path (Join-Path $FrontendDir "node_modules"))) {
    Write-Host "Installing frontend dependencies..."
    & $npm install --cache $NpmCache --no-audit --no-fund --prefix $FrontendDir
}

if (-not $SkipAkShare -and -not $SkipInstall -and -not (Test-Path $AkSharePython)) {
    Write-Host "Creating AkShare Python environment..."
    & $python -m venv $AkShareVenv
    & $AkSharePython -m pip install --upgrade pip
    & $AkSharePython -m pip install -r (Join-Path $AkShareDir "requirements.txt")
}

$backendRunArgs = if ($LocalProfile) {
    "`"-Dmaven.repo.local=$LocalMavenRepo`" `"-Dspring-boot.run.profiles=local`" spring-boot:run"
} else {
    "`"-Dmaven.repo.local=$LocalMavenRepo`" spring-boot:run"
}

$backendArgs = New-CmdArguments `
    -Executable $mvn `
    -Arguments $backendRunArgs
$frontendArgs = New-CmdArguments `
    -Executable $npm `
    -Arguments "run dev"
$akshareArgs = New-CmdArguments `
    -Executable $(if (Test-Path $AkSharePython) { $AkSharePython } else { $python }) `
    -Arguments "-m uvicorn server:app --host 0.0.0.0 --port 18080"

if ($CheckOnly) {
    Write-Host "Check only. Commands are ready:"
    Write-Host "Backend cmd.exe $backendArgs"
    if (-not $SkipAkShare) {
        Write-Host "AkShare cmd.exe $akshareArgs"
    }
    Write-Host "Frontend cmd.exe $frontendArgs"
    exit 0
}

$started = @()

try {
    if (-not $SkipAkShare) {
        Write-Host "Starting AkShare service..."
        $started += Start-DevProcess -Name "akshare" -Arguments $akshareArgs -WorkingDirectory $AkShareDir
        Start-Sleep -Seconds 2
    }

    Write-Host "Starting backend..."
    $started += Start-DevProcess -Name "backend" -Arguments $backendArgs -WorkingDirectory $Root

    Start-Sleep -Seconds 2

    Write-Host "Starting frontend..."
    $started += Start-DevProcess -Name "frontend" -Arguments $frontendArgs -WorkingDirectory $FrontendDir

    Write-Host ""
    Write-Host "Both services are starting in this window."
    Write-Host "Open http://<this-machine-ip>:5173"
    Write-Host "Press Ctrl+C to stop backend and frontend."
    Write-Host ""

    while ($true) {
        foreach ($item in $started) {
            if ($item.Process.HasExited) {
                throw "$($item.Name) exited with code $($item.Process.ExitCode)"
            }
        }
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Host ""
    Write-Host "Stopping services..."
    foreach ($item in $started) {
        Stop-DevProcessTree -Process $item.Process
    }
}
