<#
.SYNOPSIS
    Fast incremental deploy of MyHockeyStats to the Mac mini (M4).

.DESCRIPTION
    Uses SSH key authentication (see ~/.ssh/config: Host "macmini" /
    "macmini-ts" with IdentityFile). No password prompts at all.

    Flow:
      1. Verify the SSH connection (fast, no password - key auth).
      2. Detect changed files via `git status --porcelain` (or use -Files).
      3. scp each file to ~/deploy_staging/<relpath>, then move into
         ~/hockey-server/<relpath>, strip CRLF, chmod +x scripts.
      4. Rebuild + restart ONLY the affected Docker services
         (backend when backend/** changed, frontend when frontend/** changed).

.PARAMETER Files
    Comma-separated repo-relative paths to deploy (overrides git detection).

.PARAMETER Services
    Comma-separated Docker services to rebuild (backend,frontend).
    Default: auto-detected from the changed paths.

.PARAMETER NoBuild
    Only sync files + CRLF fix; skip the Docker build/restart.

.PARAMETER Connect
    Only verify the SSH connection (key auth) and exit.

.PARAMETER DryRun
    Print what would be deployed without transferring anything.

.PARAMETER Tailscale
    Connect via the Tailscale IP (100.112.42.3) instead of the LAN IP.

.EXAMPLE
    .\scripts\deploy-fast.ps1 -Connect
    # verify passwordless SSH to the Mac works

.EXAMPLE
    .\scripts\deploy-fast.ps1
    # detect changed files, deploy, rebuild affected services

.EXAMPLE
    .\scripts\deploy-fast.ps1 -Services frontend
    # deploy changed files and force-rebuild the frontend only
#>
[CmdletBinding()]
param(
    [string]$Files,
    [string]$Services,
    [switch]$NoBuild,
    [switch]$Connect,
    [switch]$DryRun,
    [switch]$Tailscale
)

$ErrorActionPreference = "Continue"
$RepoRoot  = (Resolve-Path "$PSScriptRoot\..").Path
$RemoteDir = "~/hockey-server"
$HostAlias = if ($Tailscale) { "macmini-ts" } else { "macmini" }

function Run-Ssh([string]$cmd) {
    ssh $HostAlias $cmd
    if ($LASTEXITCODE -ne 0) { throw "ssh failed: $cmd" }
}

# Parent dir of a POSIX path (returns '' when there is no slash)
function Get-PosixParent([string]$p) {
    $p = $p -replace '\\', '/'
    $i = $p.LastIndexOf('/')
    if ($i -lt 0) { return "" }
    return $p.Substring(0, $i)
}

# ─── Step 1: verify SSH connection (key auth, no password) ───────────────
Write-Host "==> Verifying SSH connection ($HostAlias)..." -ForegroundColor Yellow
ssh $HostAlias "echo connected"
if ($LASTEXITCODE -ne 0) { throw "Could not connect to $HostAlias (key not authorized? Mac asleep?)" }
Write-Host "    Connected (SSH key auth)" -ForegroundColor Green

if ($Connect) {
    Write-Host "    SSH key auth OK - deploy-fast is ready to use." -ForegroundColor Green
    exit 0
}

# ─── Step 2: determine changed files ─────────────────────────────────────
$changed = New-Object System.Collections.Generic.List[string]  # local relpaths
$deleted = New-Object System.Collections.Generic.List[string]  # local relpaths

if ($Files) {
    foreach ($f in ($Files -split "," | ForEach-Object { $_.Trim() })) {
        if ($f) { $changed.Add($f) }
    }
} else {
    git -C $RepoRoot status --porcelain | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -lt 4) { return }
        $code = $line.Substring(0, 2)
        $path = $line.Substring(3).Trim()
        if ($path -match " -> ") { $path = ($path -split " -> ")[-1].Trim() }
        $path = $path.Trim('"')
        if ($code -match "^D") { $deleted.Add($path) }
        else { $changed.Add($path) }
    }
}

if ($changed.Count -eq 0 -and $deleted.Count -eq 0) {
    Write-Host "No changed files to deploy." -ForegroundColor Gray
    exit 0
}

Write-Host ""
Write-Host "==> Changed files:" -ForegroundColor Cyan
$changed | ForEach-Object { Write-Host "   + $_" -ForegroundColor White }
$deleted | ForEach-Object { Write-Host "   - $_" -ForegroundColor DarkGray }

if ($DryRun) {
    Write-Host "`n[DryRun] no files transferred." -ForegroundColor Yellow
    exit 0
}

# ─── Step 3: scp changed files to staging ────────────────────────────────
Write-Host ""
Write-Host "==> Copying files to Mac..." -ForegroundColor Yellow
$staged = New-Object System.Collections.Generic.List[string]
foreach ($rel in $changed) {
    $relPosix = $rel -replace '\\', '/'
    $local = Join-Path $RepoRoot ($rel -replace "/", "\")
    if (-not (Test-Path $local)) {
        Write-Warning "Skipping missing local file: $rel"
        continue
    }
    $remotePath  = "$RemoteDir/$relPosix"
    $stagingPath = "~/deploy_staging/$relPosix"
    $stagingDir  = Get-PosixParent $stagingPath
    # ensure remote staging subdir
    if ($stagingDir) { ssh $HostAlias "mkdir -p $stagingDir" 2>$null }
    Write-Host "   scp $rel" -ForegroundColor White
    scp "$local" "$HostAlias`:$stagingPath"
    if ($LASTEXITCODE -ne 0) { throw "scp failed for $rel" }
    $staged.Add($rel)
}

# ─── Step 4: move into place + CRLF fix + chmod, and handle deletes ──────
Write-Host ""
Write-Host "==> Moving into $RemoteDir + CRLF fix..." -ForegroundColor Yellow
$mvCmds = @()
foreach ($rel in $staged) {
    $relPosix   = $rel -replace '\\', '/'
    $remotePath = "$RemoteDir/$relPosix"
    $parentDir  = Get-PosixParent $remotePath
    $mvCmds += "mkdir -p $parentDir; mv -f ~/deploy_staging/$relPosix $remotePath;"
}
if ($deleted.Count -gt 0) {
    foreach ($rel in $deleted) {
        $relPosix = $rel -replace '\\', '/'
        $mvCmds += "rm -f $RemoteDir/$relPosix;"
    }
}
$mvCmdsJoined  = $mvCmds -join " "
$stagedList    = ($staged | ForEach-Object { "$RemoteDir/" + ($_ -replace '\\', '/') }) -join " "
$chmodList     = ($staged | Where-Object { $_ -match "\.(sh|py)$" } | ForEach-Object { "$RemoteDir/" + ($_ -replace '\\', '/') }) -join " "
$chmodLine     = if ($chmodList) { "chmod +x $chmodList" } else { "" }

$fixScript = @"
export PATH="/usr/local/bin:`$PATH"
$mvCmdsJoined
for f in $stagedList; do perl -pi -e 's/\r$//' "`$f"; done
$chmodLine
echo DEPLOY_SYNC_DONE
"@
$fixScript = $fixScript -replace "`r`n", "`n" -replace "`r", "`n"
Run-Ssh $fixScript

# ─── Step 5: rebuild affected services ───────────────────────────────────
if ($NoBuild) {
    Write-Host ""
    Write-Host "Deploy sync complete (build skipped)." -ForegroundColor Green
    exit 0
}

$buildSvcs = New-Object System.Collections.Generic.List[string]
if ($Services) {
    foreach ($s in ($Services -split "," | ForEach-Object { $_.Trim() })) { if ($s) { $buildSvcs.Add($s) } }
} else {
    $changed + $deleted | ForEach-Object {
        if ($_ -match "^backend/") { $buildSvcs.Add("backend") }
        elseif ($_ -match "^frontend/") { $buildSvcs.Add("frontend") }
    }
}
$buildSvcs = @($buildSvcs | Sort-Object -Unique)
if ($buildSvcs.Count -eq 0) {
    Write-Host ""
    Write-Host "Only scripts/data changed - no Docker rebuild needed." -ForegroundColor Green
    exit 0
}

Write-Host ""
Write-Host "==> Building + restarting: $($buildSvcs -join ', ')" -ForegroundColor Yellow
$stamp = Get-Date -Format "yyyyMMddHHmmss"
$svcArgs = $buildSvcs -join " "

$buildScript = @"
export PATH="/usr/local/bin:`$PATH"
cd $RemoteDir
docker compose -f docker-compose.macmini.yml build $svcArgs > /tmp/deploy-build-$stamp.log 2>&1
echo BUILD_EXIT=`$?
grep -E 'BUILD SUCCESS|BUILD FAILURE|failed to solve|Image hockey-server-' /tmp/deploy-build-$stamp.log | tail -5
if grep -qE 'BUILD FAILURE|failed to solve' /tmp/deploy-build-$stamp.log; then echo BUILD_RESULT=FAIL; else echo BUILD_RESULT=OK; fi
"@
$buildScript = $buildScript -replace "`r`n", "`n" -replace "`r", "`n"
$buildOut = @(ssh $HostAlias $buildScript)
$buildOut | ForEach-Object { Write-Host $_ }
if (-not (($buildOut -join "`n") -match "BUILD_RESULT=OK")) {
    throw "Docker build failed - see /tmp/deploy-build-$stamp.log on the Mac."
}

$upScript = @"
export PATH="/usr/local/bin:`$PATH"
cd $RemoteDir
docker compose -f docker-compose.macmini.yml up -d $svcArgs 2>&1 | tail -3
sleep 6
docker ps --filter name=myhockeystats --format '{{.Names}} {{.Status}}'
"@
$upScript = $upScript -replace "`r`n", "`n" -replace "`r", "`n"
Run-Ssh $upScript

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  ✅ Deploy complete: $($buildSvcs -join ', ')" -ForegroundColor Green
Write-Host "  Build log on Mac: /tmp/deploy-build-$stamp.log" -ForegroundColor Gray
Write-Host "==========================================" -ForegroundColor Cyan
