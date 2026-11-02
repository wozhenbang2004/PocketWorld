<# Offline/frozen source build on a separately approved Windows x64 builder.
   Requires a FULL clean audited source checkout and already-cached toolchain/deps.
   Does not install tools, sign, publish, deploy, pair, run GUI or create credentials.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$expectedCommit = 'f595a4fb6f524e555e5e422aab81c3457fe4a713'
if (-not [Environment]::Is64BitProcess) { throw 'Use an approved Windows x64 build process.' }
$source = (Resolve-Path -LiteralPath $SourceRoot).Path
$head = (& git.exe -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $expectedCommit -or (& git.exe -C $source status --porcelain)) { throw 'Exact clean Cua source commit required; no checkout/reset is performed.' }
$workspace = Join-Path $source 'libs\cua-driver\rust'
$lockPath = Join-Path $workspace 'Cargo.lock'
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) { throw 'Pinned Cargo.lock missing.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory already exists; refusing to overwrite another build.' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output | Out-Null
$lockHash = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
$rust = (& rustc.exe -Vv) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Audited Rust toolchain unavailable; no installation attempted.' }
$cargo = (& cargo.exe -V) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Audited Cargo unavailable; no installation attempted.' }
$oldTarget = $env:CARGO_TARGET_DIR
$oldTelemetry = $env:CUA_DRIVER_RS_TELEMETRY_ENABLED
$oldUpdate = $env:CUA_DRIVER_RS_UPDATE_CHECK
try {
    $env:CARGO_TARGET_DIR = Join-Path $output 'target'
    $env:CUA_DRIVER_RS_TELEMETRY_ENABLED = 'false'
    $env:CUA_DRIVER_RS_UPDATE_CHECK = 'false'
    Push-Location $workspace
    try {
        # Actual upstream Cargo package/bin; no whole-workspace/perception build.
        & cargo.exe build --frozen --locked --release --package cua-driver --bin cua-driver 2>&1 | Tee-Object -FilePath (Join-Path $output 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Frozen Cua build failed; no download/retry/relaxation attempted.' }
    } finally { Pop-Location }
    if ((& git.exe -C $source rev-parse HEAD).Trim() -ne $expectedCommit -or (& git.exe -C $source status --porcelain) -or (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lockHash) { throw 'Source/lock changed during build; artifact not admitted.' }
    $built = Join-Path $env:CARGO_TARGET_DIR 'release\cua-driver.exe'
    if (-not (Test-Path -LiteralPath $built -PathType Leaf)) { throw 'Actual expected Windows artifact missing.' }
    $version = (& $built --version) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '(?<![0-9])0\.38\.0(?![0-9])') { throw 'Built Driver version mismatch.' }
    $artifact = Join-Path $output 'cua-driver.exe'
    Copy-Item -LiteralPath $built -Destination $artifact
    $provenance = @{ schemaVersion = 1; sourceCommit = $expectedCommit; sourceClean = $true; cargoLockSha256 = $lockHash;
        artifactSha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant(); driverVersion = '0.38.0';
        platform = 'windows-x64'; package = 'cua-driver'; features = @(); releaseStatus = 'prerelease';
        buildCommand = 'cargo build --frozen --locked --release --package cua-driver --bin cua-driver';
        rustc = $rust; cargo = $cargo; builtAtUtc = [DateTime]::UtcNow.ToString('o'); guiAcceptance = 'unverified';
        spacesIncluded = $false; perceptionIncluded = $false }
    [IO.File]::WriteAllText((Join-Path $output 'cua-build-provenance.json'), ($provenance | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Write-Output ('Source-built candidate: ' + $artifact + '. This does not prove physical Windows GUI behavior or a signed release.')
} finally {
    $env:CARGO_TARGET_DIR = $oldTarget
    $env:CUA_DRIVER_RS_TELEMETRY_ENABLED = $oldTelemetry
    $env:CUA_DRIVER_RS_UPDATE_CHECK = $oldUpdate
}
