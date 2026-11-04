<# User-session installer. No service, elevated driver, firewall rule or port forwarding.
   A trusted SHA-256 (and, for release use, expected Authenticode signer) is mandatory.
   This source has not been executed on a real Windows machine by the Linux build.
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(Mandatory = $true)][string]$Bundle,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [string]$ExpectedSignerThumbprint,
    [switch]$AllowUnsignedDevelopment,
    [switch]$EnableLoginStart,
    [switch]$DisableLoginStart,
    [switch]$Repair,
    [string]$Config
)
$ErrorActionPreference = 'Stop'
if ($EnableLoginStart -and $DisableLoginStart) { throw 'Choose enable or disable, not both.' }
if ($Repair -and ($EnableLoginStart -or $DisableLoginStart -or $Config)) { throw 'Repair restores only the original transaction; no new configuration/login change is accepted.' }
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows x64 is required.' }
$bundlePath = (Resolve-Path -LiteralPath $Bundle).Path
$bundleStream = [IO.File]::Open($bundlePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
$hasher = [Security.Cryptography.SHA256]::Create()
try { $actualBundleHash = [BitConverter]::ToString($hasher.ComputeHash($bundleStream)).Replace('-', '') } finally { $hasher.Dispose() }
if ($actualBundleHash -ne $ExpectedSha256) { $bundleStream.Dispose(); throw 'Bundle hash mismatch; nothing installed.' }
$bundleStream.Position = 0
$homeRoot = Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'
$releases = Join-Path $homeRoot 'releases'
$state = Join-Path $homeRoot 'state'
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$stage = Join-Path $releases ('.staging-' + [guid]::NewGuid().ToString('N'))
function Assert-PlainDirectory([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Install/state directory must not be a reparse point.' }
}
function Assert-ExistingAncestors([string]$Path) {
    $candidate = [IO.DirectoryInfo]::new([IO.Path]::GetFullPath($Path))
    while ($null -ne $candidate) {
        if (Test-Path -LiteralPath $candidate.FullName) { Assert-PlainDirectory $candidate.FullName }
        $candidate = $candidate.Parent
    }
}
function Set-PrivateDirectoryAcl([string]$Path) {
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    $owner = [Security.Principal.SecurityIdentifier]::new($sid)
    $acl.SetOwner($owner)
    foreach ($principal in @($owner, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($principal, [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit', [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Safe-Member([string]$Name) {
    if (-not $Name -or $Name.Contains('\') -or $Name.Contains(':') -or $Name.StartsWith('/') -or $Name -match '[<>"|?*\x00-\x1f]') { throw 'Unsafe archive member.' }
    foreach ($part in $Name.TrimEnd('/').Split('/')) {
        if (-not $part -or $part -in @('.', '..') -or $part.EndsWith('.') -or $part.EndsWith(' ') -or $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?$') { throw 'Unsafe Windows archive alias.' }
    }
}
if ($Repair -and -not (Test-Path -LiteralPath (Join-Path $homeRoot 'installation-transaction.json') -PathType Leaf)) { $bundleStream.Dispose(); throw 'No original interrupted installation transaction exists; nothing repaired.' }
$maintenanceAction = if ($Repair) { 'Repair the original interrupted installation using this exact trusted bundle; preserve replaced fixed-entry bytes' } else { 'Install verified lightweight user-session Companion (no credentials created)' }
if (-not $PSCmdlet.ShouldProcess($homeRoot, $maintenanceAction)) { $bundleStream.Dispose(); return }
Assert-ExistingAncestors $homeRoot
if (Test-Path -LiteralPath $homeRoot) {
    if ((Get-Acl -LiteralPath $homeRoot).GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid) { throw 'Existing install belongs to another owner.' }
}
if (-not $Repair) { New-Item -ItemType Directory -Path $homeRoot, $releases, $state -Force | Out-Null }
foreach ($path in @($homeRoot, $releases, $state)) { Assert-PlainDirectory $path }
# Private data stays outside versioned releases. No Everyone/Users ACL expansion.
if (-not $Repair) { foreach ($path in @($homeRoot, $releases, $state)) { Set-PrivateDirectoryAcl $path } }
else {
    foreach ($path in @($homeRoot, $releases, $state)) {
        $acl = Get-Acl -LiteralPath $path
        if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $sid) { throw 'Original recovery directory owner changed; nothing repaired.' }
        foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
            if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and $rule.IdentityReference.Value -notin @($sid, 'S-1-5-18')) { throw 'Recovery directory is no longer private; no ACL was changed.' }
        }
    }
}
$installLock = [IO.File]::Open((Join-Path $homeRoot 'install.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipArchive]::new($bundleStream, [IO.Compression.ZipArchiveMode]::Read, $true)
    try {
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $total = [int64]0
        if ($archive.Entries.Count -gt 20000) { throw 'Archive entry quota exceeded.' }
        foreach ($entry in $archive.Entries) {
            Safe-Member $entry.FullName
            if (-not $seen.Add($entry.FullName.TrimEnd('/'))) { throw 'Duplicate/case-colliding archive member.' }
            if (($entry.ExternalAttributes -shr 16 -band 0xF000) -eq 0xA000) { throw 'Archive symlinks are not accepted.' }
            $total += $entry.Length
            if ($entry.Length -gt 536870912 -or $total -gt 2147483648) { throw 'Archive byte quota exceeded.' }
            $target = Join-Path $stage $entry.FullName.Replace('/', [IO.Path]::DirectorySeparatorChar)
            $full = [IO.Path]::GetFullPath($target)
            if (-not $full.StartsWith($stage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Archive escaped staging root.' }
            if ($entry.FullName.EndsWith('/')) { New-Item -ItemType Directory -Path $full -Force | Out-Null; continue }
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($full)) -Force | Out-Null
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Open($full, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $inputStream.CopyTo($outputStream); $outputStream.Flush($true) } finally { $outputStream.Dispose(); $inputStream.Dispose() }
        }
    } finally { $archive.Dispose() }
    $manifest = Get-Content -LiteralPath (Join-Path $stage 'bundle-manifest.json') -Encoding UTF8 -Raw | ConvertFrom-Json
    if ($manifest.schemaVersion -ne 1 -or $manifest.product -ne 'PocketWorld-Companion' -or $manifest.platform -ne 'windows-x64' -or $manifest.version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') { throw 'Unknown bundle schema/platform/version.' }
    if ($manifest.cua.sourceCommit -ne 'f595a4fb6f524e555e5e422aab81c3457fe4a713' -or $manifest.cua.releaseStatus -ne 'prerelease' -or $manifest.cua.spacesIncluded -or $manifest.cua.perceptionIncluded) { throw 'Driver provenance/license boundary mismatch.' }
    if ($manifest.agentDesktop.sourceCommit -ne '6660fbac768dc7e7332837b90ed465684859afa3' -or $manifest.agentDesktop.contractVersion -ne '2.6' -or $manifest.agentDesktop.runtime -ne 'headless-semantic-cli') { throw 'Structured desktop driver pin mismatch.' }
    $declared = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [void]$declared.Add('bundle-manifest.json')
    foreach ($file in $manifest.files) {
        Safe-Member $file.path
        if (-not $declared.Add($file.path)) { throw 'Duplicate manifest file.' }
        $path = Join-Path $stage $file.path.Replace('/', [IO.Path]::DirectorySeparatorChar)
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.Length -ne $file.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw 'Manifest integrity mismatch.' }
    }
    foreach ($file in (Get-ChildItem -LiteralPath $stage -File -Recurse)) {
        $relative = $file.FullName.Substring($stage.Length + 1).Replace('\', '/')
        if (-not $declared.Contains($relative)) { throw 'Undeclared bundled executable or data file.' }
    }
    $exe = Join-Path $stage 'app\PocketWorld-Companion.exe'
    $driver = Join-Path $stage 'driver\cua-driver.exe'
    $semanticDriver = Join-Path $stage 'driver\agent-desktop.exe'
    foreach ($path in @($exe, $driver, $semanticDriver)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Required bundled runtime missing.' }
        if (-not $AllowUnsignedDevelopment) {
            if (-not $ExpectedSignerThumbprint) { throw 'Release install requires an independently trusted signer thumbprint.' }
            $signature = Get-AuthenticodeSignature -LiteralPath $path
            if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Thumbprint -ne $ExpectedSignerThumbprint) { throw 'Runtime signature/signer mismatch.' }
        }
    }
    if ((Get-FileHash -LiteralPath $driver -Algorithm SHA256).Hash -ne $manifest.cua.sha256) { throw 'Actual Driver binary differs from its source/build manifest.' }
    if ((Get-FileHash -LiteralPath $semanticDriver -Algorithm SHA256).Hash -ne $manifest.agentDesktop.sha256) { throw 'Actual structured Driver differs from its source/build manifest.' }
    foreach ($name in @('Install-Companion.ps1','Manage-Companion.ps1','Launch-Companion.ps1','Companion-InstallState.ps1')) {
        $scriptPath = Join-Path $stage ('tools\' + $name)
        if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw 'Required management entry is missing from bundle.' }
        if (-not $AllowUnsignedDevelopment) {
            $signature = Get-AuthenticodeSignature -LiteralPath $scriptPath
            if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Thumbprint -ne $ExpectedSignerThumbprint) { throw 'Management script signature mismatch.' }
        }
    }
    if ((Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $stage 'tools\Install-Companion.ps1') -Algorithm SHA256).Hash) { throw 'Use the installer delivered with this exact bundle.' }
    . (Join-Path $stage 'tools\Companion-InstallState.ps1')
    $paths = Get-PWPaths
    if ($Repair) {
        # This helper is from the fully hash/signature-verified SAME bundle,
        # not the possibly missing/truncated fixed Home helper/Manage script.
        Assert-PWPrivateAcl $paths.Home $sid
        Assert-PWPrivateAcl $paths.Registry $sid
        Assert-PWPrivateAcl $paths.Transaction $sid
        $original = Read-PWJson $paths.Transaction
        if ($original.nextPointer.version -ne $manifest.version -or $original.nextPointer.manifestSha256 -ne (Get-FileHash -LiteralPath (Join-Path $stage 'bundle-manifest.json') -Algorithm SHA256).Hash -or
            $original.nextPointer.allowUnsignedDevelopment -isnot [bool] -or $original.nextPointer.allowUnsignedDevelopment -ne [bool]$AllowUnsignedDevelopment -or [string]$original.nextPointer.signerThumbprint -ne [string]$ExpectedSignerThumbprint) { throw 'Repair requires the exact original transaction bundle and trust mode; record retained.' }
        Repair-PWTransaction $paths -FromVerifiedInstaller
        Write-Output 'Original transaction repaired. Fixed management entries restored from verified release bytes; replaced bytes retained. No process/task was resumed. Rerun this installer without -Repair to install/update.'
        return
    }
    if (Test-Path -LiteralPath $paths.Transaction) { throw 'An interrupted install must be repaired before updating. Rerun this exact trusted installer and bundle with -Repair; fixed Manage/Helper need not exist.' }
    Assert-PWStopped $paths
    Assert-PWFixedConfig $paths $Config
    $previous = if (Test-Path -LiteralPath $paths.Current) { Read-PWCurrent $paths } else { $null }
    $taskName = 'PocketWorld-Companion-' + $sid
    $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    $loginEnabled = $false
    if ($null -ne $previous) { $loginEnabled = [bool]$previous.loginStartEnabled -and $null -ne $existingTask -and $existingTask.State -ne 'Disabled' }
    if ($EnableLoginStart) { $loginEnabled = $true }
    if ($DisableLoginStart) { $loginEnabled = $false }
    if ($loginEnabled) { Assert-PWFixedConfig $paths $Config -Required }
    $destination = Join-Path $releases $manifest.version
    $reuseRelease = Test-Path -LiteralPath $destination
    if ($reuseRelease) {
        Assert-PWPlainPath $destination
        if ((Get-FileHash -LiteralPath (Join-Path $destination 'bundle-manifest.json') -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $stage 'bundle-manifest.json') -Algorithm SHA256).Hash) { throw 'Existing immutable version differs; no overwrite performed.' }
    }
    $installId = if ($null -ne $previous) { $previous.installId } elseif (Test-Path -LiteralPath $paths.Registry) { (Read-PWJson $paths.Registry).installId } else { [guid]::NewGuid().ToString('N') }
    if ($installId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid original installation identity.' }
    $registry = @{schemaVersion=2; ownerSid=$sid; installId=$installId; stateRoot=$state; config=$paths.Config;
        identityDir=(Join-Path $state 'identity-v1');jobs=(Join-Path $state 'jobs');logs=(Join-Path $state 'logs')}
    if (Test-Path -LiteralPath $paths.Registry) {
        Assert-PWPrivateAcl $paths.Registry $sid
        $known = Read-PWJson $paths.Registry
        foreach ($key in $registry.Keys) { if ($known.$key -ne $registry[$key]) { throw 'Existing registered state does not match the fixed installation.' } }
    } else { Write-PWJsonAtomic $paths.Registry $registry }
    if (-not $reuseRelease) { Move-Item -LiteralPath $stage -Destination $destination }
    $previousRelease = $null
    if ($null -ne $previous -and $previous.version -eq $manifest.version) { $previousRelease=$previous.previousRelease }
    elseif ($null -ne $previous) { $previousRelease = @{version=$previous.version;root=$previous.root;manifestSha256=$previous.manifestSha256;allowUnsignedDevelopment=$previous.allowUnsignedDevelopment;signerThumbprint=$previous.signerThumbprint} }
    $pointer = @{schemaVersion=2;version=$manifest.version;previousVersion=$(if ($previousRelease) {$previousRelease.version} else {''});previousRelease=$previousRelease;
        root=$destination;state=$state;config=$paths.Config;ownerSid=$sid;installId=$installId;loginStartEnabled=$loginEnabled;
        manifestSha256=(Get-FileHash -LiteralPath (Join-Path $destination 'bundle-manifest.json') -Algorithm SHA256).Hash;
        allowUnsignedDevelopment=[bool]$AllowUnsignedDevelopment;signerThumbprint=$ExpectedSignerThumbprint}
    $null = Assert-PWRelease $paths $pointer
    Set-PWCurrentTransaction $paths $pointer
    Write-Output ('Installed ' + $manifest.version + '. Login preference preserved; no task or process was resumed.')
    if (-not (Test-Path -LiteralPath $paths.Config)) { Write-Output 'Initialize the installed Companion with a workspace before starting it.' }
    if ($AllowUnsignedDevelopment) { Write-Warning 'Unsigned development package; normal release signature checks were explicitly not requested.' }

} finally {
    $bundleStream.Dispose()
    $installLock.Dispose()
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
