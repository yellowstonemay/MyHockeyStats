<#
.SYNOPSIS
    Deploy MyHockeyStats full stack to the remote Mac mini M4 server.

.DESCRIPTION
    Builds the backend & frontend Docker images on the Mac mini (ARM64 native),
    copies the project files, and starts all services via docker-compose.
    
    Also sets up Cloudflare Tunnel (cloudflared) to expose the web app publicly.

.PARAMETER MacUser
    SSH user for the Mac mini (default: ethan-macmini).

.PARAMETER MacHost
    Hostname/IP of the Mac mini (default: 192.168.1.156).

.PARAMETER MacPath
    Remote project directory on the Mac mini (default: ~/hockey-server).

.PARAMETER SkipBuild
    Skip the Docker build step (use if images are already built).

.PARAMETER SetupTunnel
    Also configure Cloudflare Tunnel after deployment.

.EXAMPLE
    .\scripts\deploy-to-macmini.ps1

.EXAMPLE
    .\scripts\deploy-to-macmini.ps1 -SetupTunnel
#>

param(
    [string]$MacUser = "ethan-macmini",
    [string]$MacHost = "192.168.1.156",
    [string]$MacPath = "~/hockey-server",
    [switch]$SkipBuild,
    [switch]$SetupTunnel
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path "$PSScriptRoot\.."
$RemoteHost = "${MacUser}@${MacHost}"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  MyHockeyStats - Deploy to Mac Mini M4   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ─── Step 1: Create deployment tarball ───────────────────────────────────
Write-Host "[1/4] Creating deployment archive..." -ForegroundColor Yellow

# Files and directories to include
$IncludeList = @(
    "docker-compose.macmini.yml",
    "backend/",
    "frontend/",
    "scripts/"
)

$ArchiveName = "myhockeystats-deploy.tar.gz"
$ArchivePath = "$env:TEMP\$ArchiveName"

# Remove old archive if it exists
Remove-Item -Path $ArchivePath -ErrorAction SilentlyContinue

# Create tar archive (PowerShell 7+ has tar, otherwise use tar.exe on Win10+)
$TarExe = if (Get-Command tar -ErrorAction SilentlyContinue) { "tar" } else { "tar.exe" }
$TarArgs = @(
    "-czf", $ArchivePath,
    "--exclude=node_modules",
    "--exclude=target",
    "--exclude=__pycache__",
    "--exclude=.git",
    "--exclude=.venv",
    "--exclude=*.vhd*",
    "--exclude=docker_data.vhdx",
    $IncludeList
)

& $TarExe $TarArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to create archive."
    exit 1
}
Write-Host "  ✓ Archive created: $ArchivePath" -ForegroundColor Green

# ─── Step 2: Copy to Mac mini via SCP ────────────────────────────────────
Write-Host "[2/4] Copying archive to Mac mini..." -ForegroundColor Yellow

# Test SSH connectivity first
Write-Host "  Testing SSH connection to ${RemoteHost}..."
ssh -o ConnectTimeout=5 -o BatchMode=yes $RemoteHost "echo OK" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ⚠ SSH key not set up — will prompt for password." -ForegroundColor Yellow
}

# Create remote directory
ssh $RemoteHost "mkdir -p $MacPath" 2>&1 | Out-Null

# Copy archive
Write-Host "  Copying archive (this may take a minute)..."
scp $ArchivePath "${RemoteHost}:${MacPath}/"
if ($LASTEXITCODE -ne 0) {
    Write-Error "SCP transfer failed."
    exit 1
}
Write-Host "  ✓ Archive copied" -ForegroundColor Green

# ─── Step 3: Extract and build on Mac mini ───────────────────────────────
Write-Host "[3/4] Extracting and building on Mac mini..." -ForegroundColor Yellow

if (-not $SkipBuild) {
    $BuildCommands = @"
cd $MacPath || exit 1
echo "  Extracting archive..."
tar xzf $ArchiveName || exit 1
rm $ArchiveName
echo "  ✓ Archive extracted"
echo ""
echo "  Building Docker images (ARM64 native)..."
docker compose -f docker-compose.macmini.yml build 2>&1
if [ \$? -eq 0 ]; then
    echo "  ✓ Build complete"
else
    echo "  ✗ Build failed"
    exit 1
fi
"@
} else {
    $BuildCommands = @"
cd $MacPath || exit 1
tar xzf $ArchiveName || exit 1
rm $ArchiveName
echo "  ✓ Archive extracted (build skipped)"
"@
}

ssh $RemoteHost $BuildCommands
if ($LASTEXITCODE -ne 0) {
    Write-Error "Build failed on Mac mini."
    exit 1
}

# ─── Step 4: Start services on Mac mini ──────────────────────────────────
Write-Host "[4/4] Starting services on Mac mini..." -ForegroundColor Yellow

$StartCommands = @"
cd $MacPath || exit 1
echo "  Stopping old containers..."
docker compose -f docker-compose.macmini.yml down 2>&1
echo "  Starting all services..."
docker compose -f docker-compose.macmini.yml up -d 2>&1
if [ \$? -eq 0 ]; then
    echo ""
    echo "  ✓ Services started!"
    echo ""
    echo "  --- Running Containers ---"
    docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
    echo ""
    echo "  --- Application URLs ---"
    echo "  Frontend: http://$(hostname):80"
    echo "  Backend:  http://$(hostname):8080"
    echo "  Health:   http://$(hostname):8080/health"
    echo "  Database: localhost:5432"
    echo ""
    echo "  --- Logs ---"
    echo "  docker logs -f myhockeystats-backend-1"
    echo "  docker logs -f myhockeystats-frontend-1"
fi
"@

ssh $RemoteHost $StartCommands
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to start services on Mac mini."
    exit 1
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  ✅ Deployment Complete!" -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Frontend: http://$MacHost`:80" -ForegroundColor White
Write-Host "  Backend:  http://$MacHost`:8080" -ForegroundColor White
Write-Host "  Health:   http://$MacHost`:8080/health" -ForegroundColor White
Write-Host ""
Write-Host "  To view logs:" -ForegroundColor Gray
Write-Host "    ssh $RemoteHost 'docker logs -f myhockeystats-backend-1'" -ForegroundColor Gray
Write-Host "    ssh $RemoteHost 'docker logs -f myhockeystats-frontend-1'" -ForegroundColor Gray
Write-Host ""

# ─── Optional: Cloudflare Tunnel Setup ──────────────────────────────────
if ($SetupTunnel) {
    Write-Host "─── Cloudflare Tunnel Setup ───" -ForegroundColor Magenta
    Write-Host ""
    Write-Host "This will guide you through setting up Cloudflare Tunnel."
    Write-Host ""
    
    $SetupTunnelCommands = @"
    # Check if cloudflared is installed
    if ! command -v cloudflared &> /dev/null; then
        echo "Installing cloudflared on Mac mini..."
        if command -v brew &> /dev/null; then
            brew install cloudflared
        else
            echo "Please install cloudflared manually:"
            echo "  brew install cloudflared"
            echo "Then re-run with: ssh $RemoteHost 'cloudflared tunnel create myhockeystats'"
            exit 1
        fi
    fi
    
    echo "cloudflared is installed."
    echo ""
    echo "To set up the tunnel, run these commands on the Mac mini:"
    echo "  1. Authenticate:  cloudflared tunnel login"
    echo "  2. Create tunnel: cloudflared tunnel create myhockeystats"
    echo "  3. Configure:     cloudflared tunnel route dns myhockeystats your-domain.com"
    echo "  4. Run tunnel:    cloudflared tunnel run myhockeystats"
    echo ""
    echo "Or run as a service with docker-compose by adding:"
    echo ""
    echo "  cloudflared:"
    echo "    image: cloudflare/cloudflared:latest"
    echo "    command: tunnel run"
    echo "    environment:"
    echo "      - TUNNEL_TOKEN=\${CLOUDFLARE_TUNNEL_TOKEN}"
    echo ""
    echo "Get your tunnel token from: https://dash.cloudflare.com/ > Zero Trust > Tunnels"
"@

    ssh $RemoteHost "$SetupTunnelCommands"
}

Write-Host ""
Write-Host "Done!" -ForegroundColor Green
