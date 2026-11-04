<# Frozen source build on a prepared Windows x64 builder.
   Requires a clean fixed upstream checkout and already-cached toolchain/deps.
   No installer, source reset, dependency download, signing, publication or GUI input.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$expectedCommit = '6660fbac768dc7e7332837b90ed465684859afa3'
$expectedLockBlob = 'de3997ec90e91217baa87fefaa3363b7784c9be9'
$targetTriple = 'x86_64-pc-windows-msvc'
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitProcess) { throw 'Use a prepared Windows x64 build process.' }
$source = (Resolve-Path -LiteralPath $SourceRoot).Path
$head = (& git.exe -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $expectedCommit -or (& git.exe -C $source status --porcelain)) {
    throw 'Exact clean agent-desktop source commit required; no checkout/reset is performed.'
}
$lockPath = Join-Path $source 'Cargo.lock'
if (-not (Test-Path -LiteralPath $lockPath -PathType Leaf)) { throw 'Pinned Cargo.lock missing.' }
$lockBlob = (& git.exe -C $source hash-object --no-filters Cargo.lock).Trim()
if ($LASTEXITCODE -ne 0 -or $lockBlob -ne $expectedLockBlob) { throw 'Cargo.lock differs from the audited source pin.' }
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Output directory exists; refusing to overwrite another build.' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output | Out-Null
$lockHash = (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant()
$rust = (& rustc.exe -Vv) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Prepared Rust toolchain unavailable; no installation attempted.' }
$cargo = (& cargo.exe -V) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Prepared Cargo unavailable; no installation attempted.' }
$oldTarget = $env:CARGO_TARGET_DIR
$oldAgentState = $env:AGENT_DESKTOP_HOME
$oldTracking = $env:DO_NOT_TRACK
try {
    $env:CARGO_TARGET_DIR = Join-Path $output 'target'
    $env:AGENT_DESKTOP_HOME = Join-Path $output 'version-probe-state'
    $env:DO_NOT_TRACK = '1'
    Push-Location $source
    try {
        & cargo.exe build --frozen --locked --release --target $targetTriple --package agent-desktop --bin agent-desktop 2>&1 | Tee-Object -FilePath (Join-Path $output 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Frozen agent-desktop build failed; no download/retry/relaxation attempted.' }
    } finally { Pop-Location }
    if ((& git.exe -C $source rev-parse HEAD).Trim() -ne $expectedCommit -or (& git.exe -C $source status --porcelain) -or
        (Get-FileHash -LiteralPath $lockPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lockHash) {
        throw 'Source/lock changed during build; artifact not admitted.'
    }
    $built = Join-Path $env:CARGO_TARGET_DIR "$targetTriple\release\agent-desktop.exe"
    if (-not (Test-Path -LiteralPath $built -PathType Leaf)) { throw 'Actual expected Windows artifact missing.' }
    $versionText = (& $built version) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Built agent-desktop version command failed.' }
    $version = $versionText | ConvertFrom-Json
    if ($version.version -ne '2.6' -or $version.command -ne 'version' -or $version.ok -ne $true -or
        $version.data.version -ne '1.1.0' -or $version.data.os -ne 'windows' -or $version.data.target -ne 'x86_64') {
        throw 'Built agent-desktop JSON/package/platform contract mismatch.'
    }
    $artifact = Join-Path $output 'agent-desktop.exe'
    Copy-Item -LiteralPath $built -Destination $artifact
    $provenance = @{ schemaVersion = 1; sourceCommit = $expectedCommit; sourceClean = $true;
        cargoLockSha256 = $lockHash; cargoLockGitBlobSha1 = $lockBlob;
        artifactSha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant();
        driverVersion = '1.1.0'; contractVersion = '2.6'; platform = 'windows-x64'; target = $targetTriple;
        package = 'agent-desktop'; license = 'Apache-2.0'; runtime = 'headless-semantic-cli';
        buildCommand = 'cargo build --frozen --locked --release --target x86_64-pc-windows-msvc --package agent-desktop --bin agent-desktop';
        rustc = $rust; cargo = $cargo; builtAtUtc = [DateTime]::UtcNow.ToString('o'); guiAcceptance = 'unverified' }
    [IO.File]::WriteAllText((Join-Path $output 'agent-desktop-build-provenance.json'), ($provenance | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Write-Output ('Source-built candidate: ' + $artifact + '. The version probe does not verify physical Windows GUI behavior or a signed release.')
} finally {
    $env:CARGO_TARGET_DIR = $oldTarget
    $env:AGENT_DESKTOP_HOME = $oldAgentState
    $env:DO_NOT_TRACK = $oldTracking
}
