#!/usr/bin/env bash
# AxioLoom — Push to GitHub and Trigger Zero-Local-SDK Cloud Build
# Run this script in Bash / WSL / macOS / Linux to push to GitHub and produce installable APKs via GitHub Actions.

set -e

echo "================================================================="
echo "   AxioLoom v2.0 - Push to GitHub & Cloud CI/CD Build Trigger    "
echo "================================================================="
echo ""

# 1. Verify Git Installation
if ! command -v git &> /dev/null; then
    echo "Error: Git is not installed or not in PATH."
    echo "Please install Git and re-run."
    exit 1
fi

# 2. Initialize Git Repository if necessary
if [ ! -d ".git" ]; then
    echo "[1/5] Initializing local git repository on branch 'main'..."
    git init -b main || (git init && git branch -M main)
else
    echo "[1/5] Git repository already initialized."
    git branch -M main 2>/dev/null || true
fi

# 3. Check and Configure Remote 'origin'
EXISTING_REMOTE=$(git remote get-url origin 2>/dev/null || true)
if [ -z "$EXISTING_REMOTE" ]; then
    echo ""
    echo "[2/5] No 'origin' remote found."
    echo "Please create an empty repository on GitHub (e.g. at https://github.com/new)."
    read -rp "Enter your GitHub repository URL (e.g. https://github.com/<username>/AxioLoom.git): " REMOTE_URL
    
    if [ -z "$REMOTE_URL" ]; then
        echo "No remote URL provided. Exiting."
        exit 1
    fi
    
    git remote add origin "$REMOTE_URL"
    echo "Remote 'origin' set to: $REMOTE_URL"
else
    echo "[2/5] Using configured remote 'origin': $EXISTING_REMOTE"
fi

# 4. Stage and Commit All Files
echo "[3/5] Staging files..."
git add .

if [ -n "$(git status --porcelain)" ]; then
    echo "[4/5] Committing changes..."
    git commit -m "Complete AxioLoom v2.0: Offline Engineering CAS, HMER, CI/CD Cloud Pipeline, and Signed APK Release"
else
    echo "[4/5] Working tree is clean. Nothing to commit."
fi

# 5. Push to GitHub
echo "[5/5] Pushing to GitHub (origin/main)..."
git push -u origin main

echo ""
echo "================================================================="
echo "   PUSH SUCCESSFUL! GitHub Actions Cloud Build Triggered        "
echo "================================================================="
echo ""
echo "What happens next:"
echo "  1. GitHub Actions runner (ubuntu-latest) has begun building automatically."
echo "     Open your repository on GitHub and click the 'Actions' tab to watch."
echo ""
echo "  2. The pipeline will:"
echo "     - Validate/generate synthetic ONNX model assets"
echo "     - Execute all 16 headless JUnit test suites (:app:testDebugUnitTest)"
echo "     - Generate ephemeral release signing keystores"
echo "     - Assemble both Debug and signed Release APKs"
echo ""
echo "  3. Download installable APKs:"
echo "     - Once the workflow finishes (~3-5 mins), scroll to the bottom of the"
echo "       workflow run page to find the 'axioloom-apks' artifact ZIP."
echo "     - Extract and install app-release.apk directly on any Android device."
echo ""
echo "  4. Publish a GitHub Release with APK assets:"
echo "     Run:  git tag v2.0.0"
echo "           git push origin v2.0.0"
echo "     GitHub Actions will automatically attach the signed APKs to the GitHub Release."
echo "================================================================="
