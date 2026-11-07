# Builds a signed release APK and publishes it to the private "releases" bucket for the in-app updater.
#   powershell -File scripts\release.ps1 -Notes "What changed"
# Needs: `npx supabase login` done once on this PC (used to fetch an upload key at runtime; nothing is stored).
#   powershell -File scripts\release.ps1 -UploadOnly   (re-upload the APK already built, same version)
param([string]$Notes = "", [switch]$UploadOnly)
$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 doesn't always offer TLS 1.2 by default, and uploads then fail with "connection closed".
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"

# The Supabase project comes from android/local.properties (supabase.url=https://<ref>.supabase.co),
# which stays out of git, so the repo doesn't name the backend.
$local = Join-Path $android "local.properties"
$urlLine = Get-Content $local -ErrorAction SilentlyContinue | Where-Object { $_ -match '^supabase\.url\s*=' } | Select-Object -First 1
if (-not $urlLine) { throw "Set supabase.url in android/local.properties" }
$BaseUrl = ($urlLine -replace '^supabase\.url\s*=\s*', '').Trim().TrimEnd('/')
if ($BaseUrl -notmatch '^https://([a-z0-9]+)\.supabase\.co$') { throw "supabase.url in local.properties doesn't look like https://<ref>.supabase.co" }
$ProjectRef = $Matches[1]

# 1. Bump the version (the updater installs anything with a higher versionCode).
$vp = Join-Path $android "version.properties"
$props = @{}
Get-Content $vp | ForEach-Object { if ($_ -match '^(\w+)=(.*)$') { $props[$Matches[1]] = $Matches[2] } }
if ($UploadOnly) {
    $code = [int]$props["versionCode"]
    $name = $props["versionName"]
    Write-Output "Re-uploading $name ($code)"
} else {
    $code = [int]$props["versionCode"] + 1
    $name = "0.$code.0"
    Set-Content -Path $vp -Value "versionCode=$code`nversionName=$name`n" -Encoding ascii -NoNewline
    Write-Output "Releasing $name ($code)"

    # 2. Build the signed, minified release APK.
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
    Push-Location $android
    try {
        & .\gradlew.bat assembleRelease --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
    } finally { Pop-Location }
}
$apk = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
$apkName = "buddy-$code.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Write-Output ("APK {0:N1} MB, sha256 {1}" -f ((Get-Item $apk).Length / 1MB), $sha.Substring(0, 12))

# 3. Upload with a server key fetched through the CLI login (kept in memory only, never printed).
Push-Location $root
# npx prints notices (e.g. "new npm version available") on stderr; with "Stop" those would abort the release.
$ErrorActionPreference = "Continue"
try {
    $keys = (npx --yes supabase projects api-keys --project-ref $ProjectRef -o json 2>$null | Out-String) | ConvertFrom-Json
} finally {
    Pop-Location
    $ErrorActionPreference = "Stop"
}
$service = ($keys | Where-Object { $_.name -eq "service_role" }).api_key
if (-not $service) { throw "Could not get an upload key - run 'npx supabase login' first" }
$headers = @{ Authorization = "Bearer $service"; apikey = $service; "x-upsert" = "true" }

# curl copes with big files and flaky links better than Invoke-RestMethod (which gave "connection closed" on ~30 MB).
& curl.exe --fail --silent --show-error --retry 3 --retry-all-errors --max-time 900 -X POST "$BaseUrl/storage/v1/object/releases/$apkName" `
    -H "Authorization: Bearer $service" -H "apikey: $service" -H "x-upsert: true" `
    -H "Content-Type: application/vnd.android.package-archive" --data-binary "@$apk" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "APK upload failed (curl exit $LASTEXITCODE)" }

$latest = @{ versionCode = $code; versionName = $name; apk = $apkName; sha256 = $sha; notes = $Notes } | ConvertTo-Json -Compress
# UTF-8 *without* BOM: Windows PowerShell's Set-Content adds one, which breaks JSON parsing in the app.
$bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($latest)
Invoke-RestMethod -Method Post -Uri "$BaseUrl/storage/v1/object/releases/latest.json" -Headers $headers `
    -ContentType "application/json" -Body $bytes | Out-Null
Write-Output "Published $apkName - open Buddy to update."
