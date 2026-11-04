<#
.SYNOPSIS
    Exports your release keystore to Base64 and copies it to clipboard
    for easy 1-click addition to GitHub Secrets.
#>

$KeystorePath = "D:\java\Key\labcraft.jks"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "  Keystore GitHub Secret Exporter                 " -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

if (-not (Test-Path $KeystorePath)) {
    Write-Error "Keystore not found at: $KeystorePath"
    exit 1
}

$bytes = [System.IO.File]::ReadAllBytes($KeystorePath)
$base64 = [System.Convert]::ToBase64String($bytes)

# Copy to clipboard
try {
    Set-Clipboard -Value $base64
    Write-Host "Keystore Base64 string COPIED TO CLIPBOARD!" -ForegroundColor Green
} catch {
    Write-Host "Could not auto-copy to clipboard. Saved to keystore-base64.txt" -ForegroundColor Yellow
}

# Also save to gitignored keystore-base64.txt
$outputTxt = Join-Path $PSScriptRoot "..\keystore-base64.txt"
Set-Content -Path $outputTxt -Value $base64 -NoNewline
Write-Host "Saved to: keystore-base64.txt (git-ignored)" -ForegroundColor Gray

Write-Host "`n--------------------------------------------------" -ForegroundColor Cyan
Write-Host "  NEXT STEPS (Add Secrets to GitHub Repository)   " -ForegroundColor Cyan
Write-Host "--------------------------------------------------" -ForegroundColor Cyan
Write-Host "1. Open GitHub Secrets URL in your browser:"
Write-Host "   https://github.com/ssilu07/chemistryLab/settings/secrets/actions" -ForegroundColor Yellow
Write-Host ""
Write-Host "2. Click 'New repository secret' and add these 4 secrets:"
Write-Host ""
Write-Host "   Secret 1: KEYSTORE_BASE64" -ForegroundColor Cyan
Write-Host "   Value   : (Already copied to your clipboard! Just press Ctrl+V)"
Write-Host ""
Write-Host "   Secret 2: KEYSTORE_PASSWORD" -ForegroundColor Cyan
Write-Host "   Value   : 8750257510"
Write-Host ""
Write-Host "   Secret 3: KEY_ALIAS" -ForegroundColor Cyan
Write-Host "   Value   : key0"
Write-Host ""
Write-Host "   Secret 4: KEY_PASSWORD" -ForegroundColor Cyan
Write-Host "   Value   : 8750257510"
Write-Host ""
Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "Once added, GitHub Actions will automatically sign your production releases!" -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan
