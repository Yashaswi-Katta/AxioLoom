# AxioLoom — Push to GitHub and Trigger Zero-Local-SDK Cloud Build
# Run this script in PowerShell to push to GitHub and produce installable APKs via GitHub Actions.

$ErrorActionPreference = "Stop"

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "   AxioLoom v2.0 - Push to GitHub & Cloud CI/CD Build Trigger    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Verify Git Installation
if (-not (Get-Command "git" -ErrorAction SilentlyContinue)) {
    Write-Host "Error: Git is not installed or not in PATH." -ForegroundColor Red
    Write-Host "Please install Git from https://git-scm.com/ and re-run." -ForegroundColor Yellow
    exit 1
}

# 2. Initialize Git Repository if necessary
if (-not (Test-Path ".git")) {
    Write-Host "[1/5] Initializing local git repository on branch 'main'..." -ForegroundColor Green
    git init -b main
    if ($LASTEXITCODE -ne 0) {
        # Fallback for older git versions
        git init
        git branch -M main
    }
} else {
    Write-Host "[1/5] Git repository already initialized." -ForegroundColor Green
    # Ensure current branch is main
    git branch -M main 2>$null
}

# 3. Check and Configure Remote 'origin'
$existingRemote = git remote get-url origin 2>$null
if (-not $existingRemote) {
    Write-Host ""
    Write-Host "[2/5] No 'origin' remote found." -ForegroundColor Yellow
    Write-Host "Please create an empty repository on GitHub (e.g. at https://github.com/new)." -ForegroundColor Yellow
    $remoteUrl = Read-Host "Enter your GitHub repository URL (e.g. https://github.com/<username>/AxioLoom.git)"
    
    if ([string]::IsNullOrWhiteSpace($remoteUrl)) {
        Write-Host "No remote URL provided. Exiting." -ForegroundColor Red
        exit 1
    }
    
    git remote add origin $remoteUrl.Trim()
    Write-Host "Remote 'origin' set to: $remoteUrl" -ForegroundColor Green
} else {
    Write-Host "[2/5] Using configured remote 'origin': $existingRemote" -ForegroundColor Green
}

# 4. Stage and Commit All Files
Write-Host "[3/5] Staging files..." -ForegroundColor Green
git add .

$status = git status --porcelain
if ($status) {
    Write-Host "[4/5] Committing changes..." -ForegroundColor Green
    git commit -m "Complete AxioLoom v2.0: Offline Engineering CAS, HMER, CI/CD Cloud Pipeline, and Signed APK Release"
} else {
    Write-Host "[4/5] Working tree is clean. Nothing to commit." -ForegroundColor Green
}

# 5. Push to GitHub
Write-Host "[5/5] Pushing to GitHub (origin/main)..." -ForegroundColor Green
git push -u origin main

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "=================================================================" -ForegroundColor Green
    Write-Host "   PUSH SUCCESSFUL! GitHub Actions Cloud Build Triggered        " -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "What happens next:" -ForegroundColor Cyan
    Write-Host "  1. GitHub Actions runner (ubuntu-latest) has begun building automatically."
    Write-Host "     Open your repository on GitHub and click the 'Actions' tab to watch."
    Write-Host ""
    Write-Host "  2. The pipeline will:"
    Write-Host "     - Validate/generate synthetic ONNX model assets"
    Write-Host "     - Execute all 16 headless JUnit test suites (:app:testDebugUnitTest)"
    Write-Host "     - Generate ephemeral release signing keystores"
    Write-Host "     - Assemble both Debug and signed Release APKs"
    Write-Host ""
    Write-Host "  3. Download installable APKs:"
    Write-Host "     - Once the workflow finishes (~3-5 mins), scroll to the bottom of the"
    Write-Host "       workflow run page to find the 'axioloom-apks' artifact ZIP."
    Write-Host "     - Extract and install app-release.apk directly on any Android device."
    Write-Host ""
    Write-Host "  4. Publish a GitHub Release with APK assets:"
    Write-Host "     Run:  git tag v2.0.0"
    Write-Host "           git push origin v2.0.0"
    Write-Host "     GitHub Actions will automatically attach the signed APKs to the GitHub Release."
    Write-Host "=================================================================" -ForegroundColor Green
} else {
    Write-Host ""
    Write-Host "Push failed. Please verify your repository URL and GitHub authentication." -ForegroundColor Red
}
