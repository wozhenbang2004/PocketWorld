<# User-session installer. No service, elevated driver, firewall rule or port forwarding.
   A trusted SHA-256 (and, for release use, expected Authenticode signer) is mandatory.
   This source has not been executed on a real Windows machine by the Linux build.
#>
[CmdletBinding(DefaultParameterSetName = 'Graphical', SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [Parameter(ParameterSetName = 'Graphical')][switch]$FirstInstall,
    [Parameter(ParameterSetName = 'Bundle', Mandatory = $true)][string]$Bundle,
    [Parameter(ParameterSetName = 'Bundle', Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [Parameter(ParameterSetName = 'Bundle')][string]$ExpectedSignerThumbprint,
    [Parameter(ParameterSetName = 'Bundle')][switch]$AllowUnsignedDevelopment,
    [Parameter(ParameterSetName = 'Bundle')][switch]$EnableLoginStart,
    [Parameter(ParameterSetName = 'Bundle')][switch]$DisableLoginStart,
    [Parameter(ParameterSetName = 'Bundle')][switch]$Repair,
    [Parameter(ParameterSetName = 'Bundle')][string]$Config
)
$ErrorActionPreference = 'Stop'
$graphicalFirst = $PSCmdlet.ParameterSetName -eq 'Graphical'
$installerPath = $PSCommandPath

function Show-PWSetupMessage([string]$Body, [string]$Title, [string]$Buttons = 'OK', [string]$Icon = 'Information') {
    $default = if ($Buttons -eq 'YesNo') { 'Button2' } else { 'Button1' }
    return [Windows.Forms.MessageBox]::Show($Body, $Title, $Buttons, $Icon, $default)
}
function Read-PWFirstSelection([string]$Installer) {
    if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitProcess -or -not [Environment]::UserInteractive -or
        [Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') { throw 'Use a normal interactive x64 Windows PowerShell desktop session. No installer or service was started.' }
    # Pre-run trust cannot be established by a running script: the owner must already have checked its
    # publisher in Explorer. The hash and certificate below are independently supplied, NEVER filled from the ZIP.
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -AssemblyName System.Drawing
    $sig = Get-AuthenticodeSignature -LiteralPath $Installer
    if ($sig.Status -ne 'Valid' -or $null -eq $sig.SignerCertificate) { throw '这个首装入口没有有效的 Windows 发布者签名。未安装；本窗口不接受未签名开发包。' }
    $pick = [Windows.Forms.OpenFileDialog]::new()
    try {
        $pick.Title = '第 1 步：选择从可信发布者取得的原 Windows Companion ZIP（不要选 APK 或源码 ZIP）'
        $pick.Filter = 'Windows Companion 程序包 (*.zip)|*.zip'
        $pick.Multiselect = $false; $pick.CheckFileExists = $true; $pick.DereferenceLinks = $false
        $pick.RestoreDirectory = $true
        if ($pick.ShowDialog() -ne 'OK') { return }
        $file = [IO.Path]::GetFullPath($pick.FileName)
    } finally { $pick.Dispose() }
    if ($file -notmatch '^[A-Za-z]:\\' -or $file -match '[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]' -or [IO.Path]::GetExtension($file) -ne '.zip' -or
        [IO.DriveInfo]::new([IO.Path]::GetPathRoot($file)).DriveType -ne 'Fixed') { throw '请选择本机固定磁盘上的完整原 ZIP。不会从网络地址下载或从快捷方式取包。' }
    Assert-ExistingAncestors ([IO.Path]::GetDirectoryName($file))
    $item = Get-Item -LiteralPath $file -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        ($item.Attributes -band 0x441000) -or $item.Length -lt 22 -or $item.Length -gt 2147483648) { throw '不是支持的本机普通 ZIP，或文件尚未保存在本机。不联网补取。' }
    $form = [Windows.Forms.Form]::new()
    try {
        $form.Text = '第 2 步：核对独立发布资料'
        $form.StartPosition = 'CenterScreen'; $form.AutoSize = $true; $form.AutoSizeMode = 'GrowAndShrink'
        $form.AutoScaleMode = 'Font'; $form.MaximizeBox = $false; $form.MinimizeBox = $false
        $grid = [Windows.Forms.TableLayoutPanel]::new()
        $grid.AutoSize = $true; $grid.ColumnCount = 1; $grid.Padding = [Windows.Forms.Padding]::new(16)
        $form.Controls.Add($grid)
        function Add-PWTrustLabel([string]$Text) {
            $label = [Windows.Forms.Label]::new(); $label.AutoSize = $true; $label.MaximumSize = [Drawing.Size]::new(570,0)
            $label.Text = $Text; $label.UseMnemonic = $false; $grid.Controls.Add($label)
        }
        Add-PWTrustLabel ('文件：' + $file)
        Add-PWTrustLabel '从你另行核对的可信发布信息粘贴这两项。不从当前 ZIP、旁边的校验文件或本机签名自动填入。没有这些资料就取消。'
        Add-PWTrustLabel '完整 ZIP 的 SHA-256（64 个十六进制字符）'
        $hash = [Windows.Forms.TextBox]::new(); $hash.Width = 550; $hash.MaxLength = 180; $grid.Controls.Add($hash)
        Add-PWTrustLabel '发布者代码签名证书指纹（40 个十六进制字符，不是 ZIP 摘要）'
        $signer = [Windows.Forms.TextBox]::new(); $signer.Width = 550; $signer.MaxLength = 140; $grid.Controls.Add($signer)
        $next = [Windows.Forms.Button]::new(); $next.AutoSize = $true; $next.Text = '核对所选包（尚不安装）'
        $next.DialogResult = 'OK'; $next.Enabled = $false; $grid.Controls.Add($next)
        $cancel = [Windows.Forms.Button]::new(); $cancel.AutoSize = $true; $cancel.Text = '取消'; $cancel.DialogResult = 'Cancel'; $grid.Controls.Add($cancel)
        $form.CancelButton = $cancel
        $validate = { $next.Enabled = ($hash.Text.Trim() -cmatch '^[A-Fa-f0-9]{64}$' -and $signer.Text.Trim() -cmatch '^[A-Fa-f0-9]{40}$') }
        $hash.Add_TextChanged($validate); $signer.Add_TextChanged($validate)
        if ($form.ShowDialog() -ne 'OK') { return }
        $h = $hash.Text.Trim(); $t = $signer.Text.Trim()
        if ($h -cnotmatch '^[A-Fa-f0-9]{64}$' -or $t -cnotmatch '^[A-Fa-f0-9]{40}$' -or $sig.SignerCertificate.Thumbprint -ne $t) {
            throw '资料或签名者不符：没有安装，不替换你输入的指纹。'
        }
        return @{file=$file; sha=$h; signer=$t}
    } finally { $form.Dispose() }
}
function Read-PWFirstManifest($Stream) {
    # Preview only a bounded, unambiguous manifest AFTER the separately approved archive SHA matched.
    Add-Type -AssemblyName System.IO.Compression
    $zip = [IO.Compression.ZipArchive]::new($Stream,[IO.Compression.ZipArchiveMode]::Read,$true)
    try {
        if ($zip.Entries.Count -gt 20000) { throw '包的条目超过原安装上限。' }
        $found = @($zip.Entries | Where-Object { $_.FullName -ieq 'bundle-manifest.json' })
        if ($found.Count -ne 1 -or $found[0].FullName -cne 'bundle-manifest.json' -or $found[0].Length -lt 1 -or $found[0].Length -gt 8388608) { throw '缺少唯一、受限的 Windows 包清单。' }
        $entry = $found[0]; $s = $entry.Open()
        try {
            $buf = [byte[]]::new([int]$entry.Length); $at = 0
            while ($at -lt $buf.Length) {
                $n = $s.Read($buf,$at,$buf.Length-$at); if ($n -le 0) { throw '程序包清单提前结束。' }; $at += $n
            }
            if ($s.ReadByte() -ne -1) { throw '程序包清单长度不符。' }
            $m = [Text.UTF8Encoding]::new($false,$true).GetString($buf) | ConvertFrom-Json
        } finally { $s.Dispose() }
        if ($m.sourceCommit -isnot [string] -or $m.version -isnot [string] -or $m.product -cne 'PocketWorld-Companion' -or $m.platform -cne 'windows-x64' -or $m.schemaVersion -ne 1 -or
            $m.codeSigning -cne 'verified_final_bytes' -or $m.sourceCommit -cnotmatch '\A[a-f0-9]{40}\z' -or
            $m.version -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._-]{0,63}\z') { throw '不是已签名的 Windows x64 Companion 正式格式；源码、开发包和 APK 不能用于这里。' }
        return @{version=$m.version; commit=$m.sourceCommit}
    } finally { $zip.Dispose(); $Stream.Position = 0 }
}
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
# The GUI path is first-install-only; command-line installation, repair and explicit dev behavior stay separate.
$firstCommitted = $false; $bundleStream = $null; $installLock = $null
try {
if ($graphicalFirst) {
    if ($WhatIfPreference) { Write-Output 'Would open the signed first-install wizard; nothing would be installed or started.'; return }
    $homeRoot = Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'
    if (Test-Path -LiteralPath $homeRoot) { throw '已存在这个用户的伴侣目录。首装窗口不会使用、覆盖或修复它。如能确认是自己原先的签名安装，可从原固定工作台进入；不认识的文件不要运行。只有留有原安装事务才走原可信包的 Repair，空目录不能证明可恢复。' }
    $selection = Read-PWFirstSelection $installerPath
    if ($null -eq $selection) { return }
    $Bundle = $selection.file; $ExpectedSha256 = $selection.sha; $ExpectedSignerThumbprint = $selection.signer
}
if ($EnableLoginStart -and $DisableLoginStart) { throw 'Choose enable or disable, not both.' }
if ($Repair -and ($EnableLoginStart -or $DisableLoginStart -or $Config)) { throw 'Repair restores only the original transaction; no new configuration/login change is accepted.' }
if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows x64 is required.' }
$bundlePath = (Resolve-Path -LiteralPath $Bundle).Path
$bundleStream = [IO.File]::Open($bundlePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
if ($graphicalFirst -and ($bundleStream.Length -lt 22 -or $bundleStream.Length -gt 2147483648)) { throw '所选原 ZIP 大小变化或超限。没有安装。' }
$hasher = [Security.Cryptography.SHA256]::Create()
try { $actualBundleHash = [BitConverter]::ToString($hasher.ComputeHash($bundleStream)).Replace('-', '') } finally { $hasher.Dispose() }
if ($actualBundleHash -ne $ExpectedSha256) { $bundleStream.Dispose(); throw 'Bundle hash mismatch; nothing installed.' }
$bundleStream.Position = 0
if ($graphicalFirst) {
    try {
        $preview = Read-PWFirstManifest $bundleStream
        $review = "程序：PocketWorld Companion $($preview.version)（Windows x64）
源码提交：$($preview.commit)
原 ZIP：$bundlePath
SHA-256：$ExpectedSha256
发布者指纹：$ExpectedSignerThumbprint
安装到当前用户：$homeRoot

下一步由原安装器逐项校验程序和管理脚本的签名与完整性。不会自动启动服务、配对、创建凭据或开启登录启动。现在安装？"
        if ((Show-PWSetupMessage $review '第 3 步：只安装到这台电脑' 'YesNo' 'Question') -ne 'Yes') { $bundleStream.Dispose(); return }
    } catch { $bundleStream.Dispose(); throw }
}
$homeRoot = Join-Path $env:LOCALAPPDATA 'PocketWorld\Companion'
$releases = Join-Path $homeRoot 'releases'
$state = Join-Path $homeRoot 'state'
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$stage = Join-Path $releases ('.staging-' + [guid]::NewGuid().ToString('N'))
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
if ($graphicalFirst) {
    # No adoption of a previous install, unknown file or version directory. CreateNew on the install lock
    # below separates a concurrently-started installer from this first-owner action.
    if (Test-Path -LiteralPath $homeRoot) { throw '首装位置已经变化；没有覆盖其他安装。' }
    if (Get-ScheduledTask -TaskName ('PocketWorld-Companion-' + $sid) -ErrorAction SilentlyContinue) { throw '已有原登录任务；首装不会替它调整启动项。' }
    $companyRoot = [IO.Path]::GetDirectoryName($homeRoot)
    if (-not (Test-Path -LiteralPath $companyRoot)) { New-Item -ItemType Directory -Path $companyRoot -ErrorAction Stop | Out-Null }
    Assert-ExistingAncestors $companyRoot
    New-Item -ItemType Directory -Path $homeRoot -ErrorAction Stop | Out-Null
    New-Item -ItemType Directory -Path $releases,$state -ErrorAction Stop | Out-Null
} elseif (-not $Repair) { New-Item -ItemType Directory -Path $homeRoot, $releases, $state -Force | Out-Null }
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
$lockMode = if ($graphicalFirst) { [IO.FileMode]::CreateNew } else { [IO.FileMode]::OpenOrCreate }
$installLock = [IO.File]::Open((Join-Path $homeRoot 'install.lock'), $lockMode, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
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
    if ($graphicalFirst -and ($null -ne $previous -or $null -ne $existingTask -or (Test-Path -LiteralPath $paths.Registry) -or
            (Test-Path -LiteralPath $paths.Config) -or (Test-Path -LiteralPath (Join-Path $releases $manifest.version)))) {
        throw '原位置已有安装、配置或启动记录；首装没有采用它，请核对原安装。'
    }
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
    Set-PWCurrentTransaction $paths $pointer -NeverTouchLoginTask:$graphicalFirst
    if ($graphicalFirst) { $firstCommitted = $true }
    Write-Output ('Installed ' + $manifest.version + '. Login preference preserved; no task or process was resumed.')
    Write-Output ('Open the current installed app to check this computer or pair a phone: ' + (Join-Path $destination 'app\PocketWorld-Companion.exe'))
    Write-Output ('Stable entry after an update: ' + (Join-Path $paths.Home 'Manage-Companion.ps1') + ' -Action OpenDesk. Opening it does not start a service.')
    if ($AllowUnsignedDevelopment) { Write-Warning 'Unsigned development package; normal release signature checks were explicitly not requested.' }

} finally {
    $bundleStream.Dispose()
    $installLock.Dispose()
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}

if ($graphicalFirst -and $firstCommitted) {
    $later = Join-Path $paths.Home 'Manage-Companion.ps1'
    $note = "已安装 $($manifest.version)。现在只打开本机工作台？首次可在那里选择一个本机工作文件夹，然后决定是否与手机配对。服务仍未启动。

以后无需输命令：在文件资源管理器找到 $later，右键选「使用 PowerShell 运行」。它默认只打开同一工作台。"
    if ((Show-PWSetupMessage $note '安装完成' 'YesNo' 'Question') -ne 'Yes') { return }
    # A no-argument frozen EXE opens ONLY the existing native owner desk. Hold the normal shared
    # installation lock while verifying the exact just-installed pointer; don't trust an updated/stale path.
    $readLock = [IO.File]::Open((Join-Path $paths.Home 'install.lock'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try {
        if (Test-Path -LiteralPath $paths.Transaction) { throw '安装事务已变化；没有启动。' }
        $now = Read-PWCurrent $paths
        if ($now.installId -cne $pointer.installId -or $now.version -cne $pointer.version -or $now.manifestSha256 -ne $pointer.manifestSha256 -or
            $now.allowUnsignedDevelopment -isnot [bool] -or $now.allowUnsignedDevelopment -or $now.signerThumbprint -ne $ExpectedSignerThumbprint) {
            throw '当前安装已不是刚才核准的版本；没有启动。'
        }
        & (Join-Path $now.root 'app\PocketWorld-Companion.exe')
        if ($LASTEXITCODE -ne 0) { throw '原工作台未正常打开或已异常退出；安装未被重做。' }
    } finally { $readLock.Dispose() }
}
} catch {
    if (-not $graphicalFirst) { throw }
    $why = $_.Exception.Message
    $heading = if ($firstCommitted) { '伴侣已安装，未确认工作台已打开' } else { '不能确认首装完成' }
    if ($env:OS -ne 'Windows_NT' -or -not [Environment]::UserInteractive) { throw }
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [void](Show-PWSetupMessage ($why + "`n`n没有自动重试、修复或启动服务。") $heading 'OK' 'Warning')
    } catch { Write-Error ($heading + ': ' + $why) }
    throw
} finally {
    # Failures before the staging try/finally (hash, target, ACL or lock checks) must not leave
    # the chosen archive or lock in use if the caller kept this PowerShell session open.
    if ($null -ne $bundleStream) { $bundleStream.Dispose() }
    if ($null -ne $installLock) { $installLock.Dispose() }
}
