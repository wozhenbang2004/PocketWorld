# Local management only. Private state is retained unless the owner explicitly deletes it.
[CmdletBinding(SupportsShouldProcess=$true,ConfirmImpact='High')]
param([Parameter(Mandatory=$true)][ValidateSet('Initialize','StopDesktop','RecoverDesktop','PairDevices','EnableLoginStart','DisableLoginStart','Repair','Rollback','Uninstall')][string]$Action,
      [string]$Config,[string]$Workspace,[switch]$DeletePrivateState)
$ErrorActionPreference='Stop'
$fixedHome=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'))
if ([IO.Path]::GetFullPath($PSScriptRoot) -ne $fixedHome) { throw 'Use the installed fixed management entry. If it is missing/damaged, run the original trusted Installer and bundle with -Repair.' }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Companion-InstallState.ps1') -PathType Leaf)) { throw 'Fixed helper is missing. Run the original trusted Installer and bundle with -Repair.' }
. (Join-Path $PSScriptRoot 'Companion-InstallState.ps1')
$paths=Get-PWPaths
if ([IO.Path]::GetFullPath($PSScriptRoot) -ne $paths.Home) { throw 'Use the installed fixed management entry.' }
Assert-PWPrivateAcl $paths.Home $paths.Sid
if ($Config -and [IO.Path]::GetFullPath($Config) -ne $paths.Config) { throw 'Installed management never follows an external config/state path.' }
$launcher=Join-Path $paths.Home 'Launch-Companion.ps1'
if ($Action -in @('Initialize','StopDesktop','RecoverDesktop','PairDevices')) {
    if ($PSCmdlet.ShouldProcess('This local Companion', $Action)) {
        & $launcher -Action $Action -Workspace $Workspace
        if ($LASTEXITCODE -ne 0) { throw 'Companion action did not complete.' }
    }
    return
}
$lock=[IO.File]::Open((Join-Path $paths.Home 'install.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    if ($Action -eq 'Repair') {
        if ($PSCmdlet.ShouldProcess($paths.Home,'Recover the original interrupted install transaction')) { Repair-PWTransaction $paths }
        return
    }
    if (Test-Path -LiteralPath $paths.Transaction) { throw 'Run Repair before another installation change.' }
    $pointer=Read-PWCurrent $paths
    Assert-PWStopped $paths
    if ($Action -in @('EnableLoginStart','DisableLoginStart')) {
        if ($PSCmdlet.ShouldProcess('Current user login preference',$Action)) {
            $pointer.loginStartEnabled=($Action -eq 'EnableLoginStart')
            Set-PWCurrentTransaction $paths $pointer
        }
        return
    }
    if ($Action -eq 'Rollback') {
        $actualTask = Get-ScheduledTask -TaskName ('PocketWorld-Companion-' + $paths.Sid) -ErrorAction SilentlyContinue
        if ($null -eq $actualTask -or $actualTask.State -eq 'Disabled') { $pointer.loginStartEnabled=$false }
        if ($null -eq $pointer.previousRelease) { throw 'No exact previous release is recorded.' }
        $old=@{version=$pointer.version;root=$pointer.root;manifestSha256=$pointer.manifestSha256;allowUnsignedDevelopment=$pointer.allowUnsignedDevelopment;signerThumbprint=$pointer.signerThumbprint}
        $previous=$pointer.previousRelease
        foreach ($key in $old.Keys) { $pointer.$key=$previous.$key }
        $pointer.previousVersion=$old.version;$pointer.previousRelease=$old
        $null=Assert-PWRelease $paths $pointer
        if ($PSCmdlet.ShouldProcess($pointer.version,'Rollback verified release without resuming old operations')) { Set-PWCurrentTransaction $paths $pointer }
        return
    }
    function Assert-RemovalTree([string]$Path) {
        Assert-PWPrivateAcl $Path $paths.Sid
        $item=Get-Item -LiteralPath $Path -Force
        if ($item.PSIsContainer) { foreach ($child in Get-ChildItem -LiteralPath $Path -Force) { Assert-RemovalTree $child.FullName } }
    }
    Assert-RemovalTree $paths.Releases
    if ($PSCmdlet.ShouldProcess($paths.Releases,'Remove installed binaries and login task; preserve private state')) {
        Set-PWLoginTask $paths $false
        Remove-Item -LiteralPath $paths.Releases -Recurse -Force
        Remove-Item -LiteralPath $paths.Current -Force
        Write-Output 'Binaries removed. Private state retained; remote revocation still requires acknowledgement on the other device.'
    } else { return }
    if ($DeletePrivateState -and $PSCmdlet.ShouldContinue('Permanently delete the registered local identity, pairings, tasks and logs? Reinstalling cannot recover them.','Delete private state') -and $PSCmdlet.ShouldProcess($paths.State,'Permanently remove only this installation registered private state')) {
        $registry=Read-PWJson $paths.Registry
        if ($registry.ownerSid -ne $paths.Sid -or $registry.installId -ne $pointer.installId -or $registry.stateRoot -ne $paths.State) { throw 'Private state ownership changed; retained.' }
        Assert-RemovalTree $paths.State
        Remove-Item -LiteralPath $paths.State -Recurse -Force
        Remove-Item -LiteralPath $paths.Registry -Force
    }
} finally { $lock.Dispose() }
