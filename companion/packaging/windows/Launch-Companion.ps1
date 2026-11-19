# Stable per-user entry. Login tasks never target a version directory directly.
[CmdletBinding()]
param([ValidateSet('Serve','OpenDesk','Initialize','StopDesktop','RecoverDesktop','PairDevices','Health','ListHarnesses','EnableHarness')][string]$Action='Serve',
      [string]$Workspace, [string]$Environment, [string]$Harness, [string]$HarnessDefinition, [switch]$AllowHarnessWrite,
      [ValidatePattern('^[0-9a-f]{64}$')][string]$ReviewedConfigSha256, [switch]$PhoneReceipts, [switch]$PhoneWork,
      [ValidatePattern('^[a-f0-9]{32}$')][string]$OwnerDeskInstallId, [ValidatePattern('^[a-fA-F0-9]{40}$')][string]$OwnerDeskSigner)
$ErrorActionPreference='Stop'
if (($OwnerDeskInstallId -or $OwnerDeskSigner) -and ($Action -ne 'OpenDesk' -or -not $OwnerDeskInstallId -or -not $OwnerDeskSigner -or $Workspace)) {
    throw 'An owner Start-menu link can only open its original signed desk.'
}
if ($ReviewedConfigSha256 -and $Action -ne 'Serve') { throw 'Reviewed settings only narrow the installed Serve action.' }
if ($PhoneReceipts -and $PhoneWork) { throw 'Choose one explicit local owner entry.' }
if (($PhoneReceipts -or $PhoneWork) -and ($Action -ne 'Serve' -or -not $ReviewedConfigSha256)) { throw 'The chosen local entry needs an explicitly reviewed installed service start.' }
if ($Action -notin @('ListHarnesses','EnableHarness') -and ($Environment -or $Harness -or $HarnessDefinition -or $AllowHarnessWrite)) {
    throw 'Harness arguments require the explicit ListHarnesses or EnableHarness owner action.'
}
if ($Action -eq 'ListHarnesses' -and ($Harness -or $HarnessDefinition -or $AllowHarnessWrite)) { throw 'Discovery cannot change the owner configuration.' }
if ($Action -eq 'EnableHarness' -and (-not $Environment -or -not $Harness)) { throw 'Choose an existing Environment and a Harness ID explicitly.' }
$homeRoot=Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'
if ([IO.Path]::GetFullPath($PSScriptRoot) -ne [IO.Path]::GetFullPath($homeRoot)) { throw 'Use the installed fixed launcher.' }
if (Test-Path -LiteralPath (Join-Path $homeRoot 'installation-transaction.json')) { throw 'Installation needs Manage Repair before startup.' }
. (Join-Path $PSScriptRoot 'Companion-InstallState.ps1')
$paths=Get-PWPaths
$launchLock=[IO.File]::Open((Join-Path $paths.Home 'install.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
try {
$pointer=Read-PWCurrent $paths
if ($OwnerDeskInstallId -and ($pointer.installId -cne $OwnerDeskInstallId -or $pointer.allowUnsignedDevelopment -isnot [bool] -or
        $pointer.allowUnsignedDevelopment -or $pointer.signerThumbprint -ne $OwnerDeskSigner)) {
    throw 'The original signed installation for this Start-menu entry cannot be verified; no program was opened.'
}
foreach ($name in @('Launch-Companion.ps1','Companion-InstallState.ps1')) {
    if ((Get-FileHash -LiteralPath (Join-Path $homeRoot $name) -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $pointer.root ('tools\'+$name)) -Algorithm SHA256).Hash) { throw 'Fixed launcher differs from the verified current release.' }
}
$exe=Join-Path $pointer.root 'app\PocketWorld-Companion.exe'
if ($Action -eq 'OpenDesk') {
    & $exe
} elseif ($Action -eq 'Initialize') {
    if (-not $Workspace) { throw 'Choose a workspace explicitly for first initialization.' }
    if (Test-Path -LiteralPath $paths.Config) { throw 'Configuration already exists; it was not reset.' }
    & $exe --init --config $paths.Config --workspace $Workspace
} elseif ($Action -eq 'Health') { & $exe --health }
else {
    Assert-PWFixedConfig $paths $paths.Config -Required
    if ($Action -eq 'Serve') {
        if ($PhoneWork) { & $exe --serve --config $paths.Config --reviewed-config-sha256 $ReviewedConfigSha256 --owner-phone-work }
        elseif ($PhoneReceipts) { & $exe --serve --config $paths.Config --reviewed-config-sha256 $ReviewedConfigSha256 --owner-phone-receipts }
        elseif ($ReviewedConfigSha256) { & $exe --serve --config $paths.Config --reviewed-config-sha256 $ReviewedConfigSha256 }
        else { & $exe --serve --config $paths.Config }
    }
    elseif ($Action -in @('ListHarnesses','EnableHarness')) {
        $harnessArguments = @('--config', $paths.Config)
        if ($Action -eq 'ListHarnesses') { $harnessArguments += '--list-harnesses' }
        else { $harnessArguments += @('--enable-harness', $Harness) }
        if ($Environment) { $harnessArguments += @('--environment', $Environment) }
        if ($HarnessDefinition) { $harnessArguments += @('--harness-definition', $HarnessDefinition) }
        if ($AllowHarnessWrite) { $harnessArguments += '--allow-harness-write' }
        & $exe @harnessArguments
    }
    elseif ($Action -eq 'PairDevices') { & $exe --pairing --config $paths.Config }
    elseif ($Action -eq 'StopDesktop') { & $exe --local-stop --config $paths.Config }
    else { & $exe --recover-desktop --config $paths.Config --identity-dir (Join-Path $paths.State 'identity-v1') }
}
if ($LASTEXITCODE -ne 0) { throw ('Companion action failed with exit code '+$LASTEXITCODE) }

} finally { $launchLock.Dispose() }
