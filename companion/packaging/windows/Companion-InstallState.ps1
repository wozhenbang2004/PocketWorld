# Shared local installation authority. Never accepts cleanup roots from a remote request.
Set-StrictMode -Version Latest
function Get-PWPaths {
    $installHome = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'))
    $state = Join-Path $installHome 'state'
    return @{ Home=$installHome; State=$state; Config=(Join-Path $state 'companion.json'); Releases=(Join-Path $installHome 'releases');
        Current=(Join-Path $installHome 'current.json'); Registry=(Join-Path $installHome 'install-state.json');
        Transaction=(Join-Path $installHome 'installation-transaction.json'); Sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
}
function Assert-PWPlainPath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'A reparse path is not an installation authority.' }
    $parent = [IO.DirectoryInfo]::new([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path)))
    while ($null -ne $parent) {
        if ((Get-Item -LiteralPath $parent.FullName -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Installation ancestor is a reparse point.' }
        $parent = $parent.Parent
    }
}
function Assert-PWPrivateAcl([string]$Path, [string]$Sid) {
    Assert-PWPlainPath $Path
    $acl = Get-Acl -LiteralPath $Path
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $Sid) { throw 'Private installation owner changed.' }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and $rule.IdentityReference.Value -notin @($Sid, 'S-1-5-18')) { throw 'Private state has permissions for another principal.' }
    }
}
function Write-PWJsonAtomic([string]$Path, $Value) {
    $temp = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 32))
    $stream = [IO.File]::Open($temp, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) } finally { $stream.Dispose() }
    # Preserve both the temporary and unique backup on a partial Replace failure.
    if (Test-Path -LiteralPath $Path) { [IO.File]::Replace($temp, $Path, ($Path + '.backup-' + [guid]::NewGuid().ToString('N'))) }
    else { [IO.File]::Move($temp, $Path) }
}
function Read-PWJson([string]$Path) { Assert-PWPlainPath $Path; return (Get-Content -LiteralPath $Path -Encoding UTF8 -Raw | ConvertFrom-Json) }
function Assert-PWFixedConfig($Paths, [string]$Config, [switch]$Required) {
    if ($Config -and [IO.Path]::GetFullPath($Config) -ne $Paths.Config) { throw 'Installed Companion uses state\companion.json. External configuration is not imported or deleted.' }
    if (-not (Test-Path -LiteralPath $Paths.Config -PathType Leaf)) { if ($Required) { throw 'Initialize the local configuration before enabling login start.' }; return }
    Assert-PWPrivateAcl $Paths.Config $Paths.Sid
    $configValue = Read-PWJson $Paths.Config
    if ([IO.Path]::GetFullPath($configValue.stateDir) -ne (Join-Path $Paths.State 'jobs')) { throw 'Config stateDir must be the registered private jobs directory.' }
    if ([IO.Path]::GetFullPath($configValue.tokenFile) -ne (Join-Path $Paths.State 'companion-token.txt')) { throw 'Config tokenFile must remain in registered private state.' }
}
function Assert-PWStopped($Paths) {
    $images = Get-CimInstance Win32_Process -Filter "Name = 'PocketWorld-Companion.exe'" -ErrorAction Stop
    foreach ($image in $images) {
        if (-not $image.ExecutablePath) { throw 'Could not verify a Companion process owner/path. Exit it before maintenance.' }
        if ($image.ExecutablePath.StartsWith($Paths.Releases + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Exit the original Companion before maintenance. No process was killed or resumed.' }
    }
}
function Assert-PWRelease($Paths, $Pointer) {
    if ($Pointer.schemaVersion -ne 2 -or $Pointer.ownerSid -ne $Paths.Sid -or $Pointer.version -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$') { throw 'Installation pointer identity/version mismatch.' }
    $expected = Join-Path $Paths.Releases $Pointer.version
    if ([IO.Path]::GetFullPath($Pointer.root) -ne $expected -or [IO.Path]::GetFullPath($Pointer.state) -ne $Paths.State -or [IO.Path]::GetFullPath($Pointer.config) -ne $Paths.Config) { throw 'Installation pointer escapes registered paths.' }
    Assert-PWPrivateAcl $Paths.Home $Paths.Sid; Assert-PWPlainPath $expected
    $manifestPath = Join-Path $expected 'bundle-manifest.json'
    if ((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -ne $Pointer.manifestSha256) { throw 'Pinned release manifest changed.' }
    $manifest = Read-PWJson $manifestPath
    if ($manifest.product -ne 'PocketWorld-Companion' -or $manifest.version -ne $Pointer.version -or $manifest.platform -ne 'windows-x64') { throw 'Release manifest identity mismatch.' }
    $declared = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    [void]$declared.Add('bundle-manifest.json')
    if ($manifest.files.Count -gt 20000) { throw 'Installed manifest exceeds file quota.' }
    foreach ($file in $manifest.files) {
        if (-not $declared.Add($file.path)) { throw 'Duplicate installed manifest path.' }
        if (-not $file.path -or $file.path -match '(^/|\\|:|(^|/)\.\.(/|$))') { throw 'Unsafe manifest path.' }
        $path = Join-Path $expected $file.path.Replace('/', '\'); Assert-PWPlainPath $path
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer -or $item.Length -ne $file.bytes -or (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $file.sha256) { throw 'Installed release bytes changed.' }
    }
    foreach ($installedFile in Get-ChildItem -LiteralPath $expected -File -Recurse -Force) {
        $relative = $installedFile.FullName.Substring($expected.Length+1).Replace('\','/')
        if (-not $declared.Contains($relative)) { throw 'Unregistered file in immutable release.' }
    }
    if (-not $Pointer.allowUnsignedDevelopment) {
        foreach ($relative in @('app\PocketWorld-Companion.exe', 'driver\cua-driver.exe', 'driver\agent-desktop.exe')) {
            $signature = Get-AuthenticodeSignature -LiteralPath (Join-Path $expected $relative)
            if ($signature.Status -ne 'Valid' -or -not $Pointer.signerThumbprint -or $signature.SignerCertificate.Thumbprint -ne $Pointer.signerThumbprint) { throw 'Installed executable signer mismatch.' }
        }
    }
    return $manifest
}
function Read-PWCurrent($Paths) {
    Assert-PWPrivateAcl $Paths.Current $Paths.Sid
    $pointer = Read-PWJson $Paths.Current
    $registry = Read-PWJson $Paths.Registry
    if ($registry.schemaVersion -ne 2 -or $registry.ownerSid -ne $Paths.Sid -or $registry.installId -ne $pointer.installId -or $registry.stateRoot -ne $Paths.State -or $registry.config -ne $Paths.Config) { throw 'Registered private state identity mismatch.' }
    $null = Assert-PWRelease $Paths $pointer
    return $pointer
}
function Set-PWLoginTask($Paths, [bool]$Enabled) {
    $name = 'PocketWorld-Companion-' + $Paths.Sid
    if ($Enabled) {
        Assert-PWFixedConfig $Paths $Paths.Config -Required
        $launcher = Join-Path $Paths.Home 'Launch-Companion.ps1'
        $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $action = New-ScheduledTaskAction -Execute $shell -Argument ('-NoProfile -File "' + $launcher + '" -Action Serve')
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $Paths.Sid
        $principal = New-ScheduledTaskPrincipal -UserId $Paths.Sid -LogonType Interactive -RunLevel Limited
        Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Description 'PocketWorld current verified release in this user session.' -Force | Out-Null
    } else { if (Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $name -Confirm:$false } }
}
function Get-PWFixedEntryHash($Manifest, [string]$Name) {
    if ($Name -notin @('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1')) { throw 'Unknown fixed management entry.' }
    $records = @($Manifest.files | Where-Object { $_.path -ceq ('tools/' + $Name) })
    if ($records.Count -ne 1 -or $records[0].sha256 -notmatch '^[a-fA-F0-9]{64}$' -or $records[0].bytes -gt 1048576) { throw 'Exact bounded management entry missing from verified manifest.' }
    return [string]$records[0].sha256
}
function Set-PWFixedEntryAtomic($Paths, [string]$Name, [string]$Source, [string]$SourceHash, [string]$ExpectedCurrentHash, [string]$TransactionId) {
    if ($Paths.Home -ne (Get-PWPaths).Home -or $Name -notin @('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1') -or $TransactionId -notmatch '^[a-f0-9]{32}$') { throw 'Fixed-entry publication escaped original installation scope.' }
    if ($SourceHash -notmatch '^[a-fA-F0-9]{64}$' -or ($ExpectedCurrentHash -and $ExpectedCurrentHash -notmatch '^[a-fA-F0-9]{64}$')) { throw 'Exact entry byte identities required.' }
    Assert-PWPrivateAcl $Paths.Home $Paths.Sid
    Assert-PWPlainPath $Source
    $destination = Join-Path $Paths.Home $Name
    $temporary = Join-Path $Paths.Home ($Name + '.new-' + [guid]::NewGuid().ToString('N'))
    $inputStream = [IO.File]::Open($Source, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        if ($inputStream.Length -gt 1048576) { throw 'Fixed entry exceeds byte bound.' }
        $outputStream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $inputStream.CopyTo($outputStream); $outputStream.Flush($true) } finally { $outputStream.Dispose() }
    } finally { $inputStream.Dispose() }
    if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $SourceHash) { throw 'Pinned entry source changed; temporary retained, no publication dispatched.' }
    if (Test-Path -LiteralPath $destination) {
        Assert-PWPrivateAcl $destination $Paths.Sid
        if (-not $ExpectedCurrentHash -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $ExpectedCurrentHash) { throw 'Fixed entry changed; original bytes retained, no replacement dispatched.' }
        $backup = Join-Path $Paths.Home ($Name + '.replaced-' + $TransactionId + '-' + [guid]::NewGuid().ToString('N'))
        # Atomic namespace replacement never truncates the fixed script. Keep
        # unique before-images on success AND partial native replacement errors.
        [IO.File]::Replace($temporary, $destination, $backup)
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -ne $ExpectedCurrentHash) { throw 'Replacement crossed an unexpected edit; backup and transaction retained, never auto-replay.' }
    } else {
        if ($ExpectedCurrentHash) { throw 'Original fixed entry disappeared; temporary and transaction retained.' }
        [IO.File]::Move($temporary, $destination) # No overwrite if another file appeared.
    }
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $SourceHash) { throw 'Fixed entry publication remains unverified; recovery material retained.' }
}
function Repair-PWTransaction($Paths, [switch]$FromVerifiedInstaller) {
    if (-not (Test-Path -LiteralPath $Paths.Transaction)) { return }
    Assert-PWPrivateAcl $Paths.Transaction $Paths.Sid
    $journal = Read-PWJson $Paths.Transaction
    if ($journal.ownerSid -ne $Paths.Sid -or $journal.schemaVersion -ne 1 -or $journal.id -notmatch '^[a-f0-9]{32}$') { throw 'Unknown installation recovery record; no cleanup performed.' }
    if ($journal.phase -notin @('prepared', 'committed')) { throw 'Unknown transaction phase; original record retained.' }
    Assert-PWPrivateAcl $Paths.Registry $Paths.Sid
    $registry = Read-PWJson $Paths.Registry
    if ($registry.schemaVersion -ne 2 -or $registry.ownerSid -ne $Paths.Sid -or $registry.installId -ne $journal.nextPointer.installId -or $registry.stateRoot -ne $Paths.State -or $registry.config -ne $Paths.Config) { throw 'Original transaction no longer owns the registered installation.' }
    $nextManifest = Assert-PWRelease $Paths $journal.nextPointer
    if ($journal.phase -eq 'committed') { $null = Read-PWCurrent $Paths; Remove-Item -LiteralPath $Paths.Transaction; return }
    Assert-PWStopped $Paths
    $restorePointer = if ($null -ne $journal.previousPointer) { $journal.previousPointer } else { $journal.nextPointer }
    if ($restorePointer.installId -ne $registry.installId) { throw 'Previous installation identity changed; no repair dispatched.' }
    $restoreManifest = Assert-PWRelease $Paths $restorePointer
    foreach ($name in @('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1')) {
        $destination = Join-Path $Paths.Home $name
        $currentHash = ''
        $restoreHash = Get-PWFixedEntryHash $restoreManifest $name
        $nextHash = Get-PWFixedEntryHash $nextManifest $name
        if (Test-Path -LiteralPath $destination) {
            Assert-PWPrivateAcl $destination $Paths.Sid
            if ((Get-Item -LiteralPath $destination -Force).PSIsContainer -or (Get-Item -LiteralPath $destination -Force).Length -gt 1048576) { throw 'Unknown fixed-entry object retained; no repair dispatched.' }
            $currentHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
            if ($currentHash -notin @($restoreHash, $nextHash)) {
                if (-not $FromVerifiedInstaller) { throw 'Interrupted/edited management bytes require the original trusted Installer -Repair; bytes retained.' }
                # Explicit same-bundle repair owns ONLY these three fixed private
                # paths. Unknown/truncated bytes survive in a unique backup;
                # no arbitrary external script/path is executed or overwritten.
                Write-Warning ('Preserving interrupted/edited fixed entry before restoring verified bytes: ' + $name)
            }
        }
        if ($currentHash -ne $restoreHash) { Set-PWFixedEntryAtomic $Paths $name (Join-Path $restorePointer.root ('tools\' + $name)) $restoreHash $currentHash $journal.id }
    }
    $taskName = 'PocketWorld-Companion-' + $Paths.Sid
    if ($journal.previousTaskXml) { Register-ScheduledTask -TaskName $taskName -Xml $journal.previousTaskXml -Force | Out-Null }
    elseif (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }
    if ($null -ne $journal.previousPointer) { Write-PWJsonAtomic $Paths.Current $journal.previousPointer }
    elseif (Test-Path -LiteralPath $Paths.Current) {
        $current = Read-PWJson $Paths.Current
        if ($current.installId -ne $journal.nextPointer.installId -or $current.version -ne $journal.nextPointer.version) { throw 'Recovery would remove an unrelated pointer; retained.' }
        Remove-Item -LiteralPath $Paths.Current
    }
    Remove-Item -LiteralPath $Paths.Transaction
}
function Set-PWCurrentTransaction($Paths, $NextPointer) {
    if (Test-Path -LiteralPath $Paths.Transaction) { throw 'An interrupted installation needs explicit Manage Repair before another change.' }
    Assert-PWStopped $Paths
    if ($NextPointer.loginStartEnabled) { Assert-PWFixedConfig $Paths $Paths.Config -Required }
    $previous = if (Test-Path -LiteralPath $Paths.Current) { Read-PWCurrent $Paths } else { $null }
    $nextManifest = Assert-PWRelease $Paths $NextPointer
    $previousManifest = if ($null -ne $previous) { Assert-PWRelease $Paths $previous } else { $null }
    $taskName = 'PocketWorld-Companion-' + $Paths.Sid
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    $oldXml = if ($task) { Export-ScheduledTask -TaskName $taskName } else { '' }
    $journal = @{schemaVersion=1;id=[guid]::NewGuid().ToString('N');ownerSid=$Paths.Sid;phase='prepared';previousPointer=$previous;previousTaskXml=$oldXml;nextPointer=$NextPointer}
    $entryIdentities = @{}
    foreach ($name in @('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1')) {
        $oldPath = Join-Path $Paths.Home $name
        $entryIdentities[$name] = ''
        if (Test-Path -LiteralPath $oldPath) {
            Assert-PWPrivateAcl $oldPath $Paths.Sid
            $expected = if ($null -ne $previousManifest) { Get-PWFixedEntryHash $previousManifest $name } else { Get-PWFixedEntryHash $nextManifest $name }
            $entryIdentities[$name] = (Get-FileHash -LiteralPath $oldPath -Algorithm SHA256).Hash
            if ($entryIdentities[$name] -ne $expected) { throw 'Unknown existing fixed-entry bytes retained. Inspect/restore verified management entries before retrying; no transaction was prepared.' }
            Copy-Item -LiteralPath $oldPath -Destination ($oldPath + '.previous-' + $journal.id)
        }
    }
    Write-PWJsonAtomic $Paths.Transaction $journal
    try {
        foreach ($name in @('Launch-Companion.ps1', 'Manage-Companion.ps1', 'Companion-InstallState.ps1')) {
            Set-PWFixedEntryAtomic $Paths $name (Join-Path $NextPointer.root ('tools\' + $name)) (Get-PWFixedEntryHash $nextManifest $name) $entryIdentities[$name] $journal.id
        }
        Set-PWLoginTask $Paths ([bool]$NextPointer.loginStartEnabled)
        Write-PWJsonAtomic $Paths.Current $NextPointer
        $journal.phase = 'committed'; Write-PWJsonAtomic $Paths.Transaction $journal
        Remove-Item -LiteralPath $Paths.Transaction
    } catch {
        $failure = $_
        try { Repair-PWTransaction $Paths } catch { throw ('Installation was not committed; recovery record retained. Use fixed Manage Repair, or the original trusted Installer -Repair if fixed entries are missing/damaged. ' + $_.Exception.Message) }
        throw $failure
    }
}
