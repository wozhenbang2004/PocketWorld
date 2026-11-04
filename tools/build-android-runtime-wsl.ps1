param(
    [Parameter(Mandatory=$true)][string]$SourceRoot,
    [Parameter(Mandatory=$true)][string]$Output,
    [Parameter(Mandatory=$true)][string]$Distribution,
    [Parameter(Mandatory=$true)][string]$Ndk,
    [string]$SourceCache,
    [switch]$Offline
)
$ErrorActionPreference='Stop'
function Get-Sha256([string]$Path){
    $stream=[IO.File]::OpenRead($Path)
    $hash=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
    finally{$hash.Dispose();$stream.Dispose()}
}
$SourceRoot=[IO.Path]::GetFullPath($SourceRoot)
$Output=[IO.Path]::GetFullPath($Output)
$expectedOutput=Join-Path $SourceRoot 'app\build\generated\android-runtime'
if($Output.TrimEnd('\','/') -ne $expectedOutput.TrimEnd('\','/')){throw 'Runtime output must be the project-owned generated directory.'}
$builder=Join-Path $SourceRoot 'tools\build-android-runtime.py'
$lockPath=Join-Path $SourceRoot 'app\src\main\java\dev\pocketengine\features\local\runtime\native\source-lock.json'
$catalogPath=Join-Path $SourceRoot 'app\src\main\assets\runtime\catalog.json'
$lock=Get-Content -Raw -LiteralPath $lockPath | ConvertFrom-Json
$catalog=Get-Content -Raw -LiteralPath $catalogPath | ConvertFrom-Json
$lockSha=(Get-Sha256 $lockPath)
$builderSha=(Get-Sha256 $builder)
if($catalog.engine.sourceLockSha256 -cne $lockSha){throw 'Runtime catalogue does not match source lock.'}
$expected=@{'libproot.so'=$catalog.engine.prootSha256;'libloader.so'=$catalog.engine.loaderSha256}
function Test-VerifiedRuntime([string]$Directory){
    try{
        $receipt=Get-Content -Raw -LiteralPath (Join-Path $Directory 'native-build-receipt.json') | ConvertFrom-Json
        if($receipt.schema -ne 'pocketworld.android-native-build.v1' -or $receipt.builderSha256 -cne $builderSha -or $receipt.sourceLockSha256 -cne $lockSha -or $receipt.ndkVersion -ne $lock.ndkVersion -or $receipt.abi -ne $lock.abi -or $receipt.api -ne $lock.api -or @($receipt.payloads).Count -ne 2){return $false}
        foreach($name in $expected.Keys){
            $file=Join-Path $Directory ('jniLibs\'+$lock.abi+'\'+$name)
            $item=@($receipt.payloads | Where-Object file -CEQ $name)
            if($item.Count -ne 1 -or $item[0].sha256 -cne $expected[$name] -or $item[0].bytes -ne (Get-Item -LiteralPath $file).Length -or (Get-Sha256 $file) -cne $expected[$name]){return $false}
        }
        return $true
    }catch{return $false}
}
if(Test-VerifiedRuntime $Output){Write-Output 'Verified local PRoot output; no WSL build needed.';return}
$env:WSLENV=(@(($env:WSLENV -split ':') | Where-Object {$_})+@('HTTPS_PROXY/u','HTTP_PROXY/u','NO_PROXY/u') | Select-Object -Unique) -join ':'
$sourceLinux=(& wsl.exe --distribution $Distribution --exec wslpath -a -u $SourceRoot | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $sourceLinux -notmatch '^/[^\r\n]+$'){throw 'Could not resolve source path in selected WSL distribution.'}
$key=$lockSha.Substring(0,16)+'-'+$builderSha.Substring(0,16)
$cacheLinux=(& wsl.exe --distribution $Distribution --exec python3 -c "from pathlib import Path;import sys;print(Path.home()/'.cache'/'pocketworld-native'/sys.argv[1])" $key | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or $cacheLinux -notmatch '^/[^\r\n]+$'){throw 'Could not resolve isolated Linux runtime cache.'}
$cacheWindows=(& wsl.exe --distribution $Distribution --exec wslpath -w $cacheLinux | Out-String).Trim()
if($LASTEXITCODE -ne 0 -or -not $cacheWindows.StartsWith('\\')){throw 'Could not resolve Linux artifact handoff path.'}
if(-not (Test-VerifiedRuntime $cacheWindows)){
    $nativeArgs=@('--distribution',$Distribution,'--exec','python3',($sourceLinux+'/tools/build-android-runtime.py'),'--ndk',$Ndk,'--output',$cacheLinux)
    if($SourceCache){
        $cacheSourceLinux=(& wsl.exe --distribution $Distribution --exec wslpath -a -u ([IO.Path]::GetFullPath($SourceCache)) | Out-String).Trim()
        if($LASTEXITCODE -ne 0 -or $cacheSourceLinux -notmatch '^/[^\r\n]+$'){throw 'Could not resolve runtime source cache in WSL.'}
        $nativeArgs+=@('--cache',$cacheSourceLinux)
    }
    if($Offline){$nativeArgs+='--offline'}
    & wsl.exe @nativeArgs
    if($LASTEXITCODE -ne 0){throw 'Linux PRoot cross-build failed; no unverified payload was copied.'}
    if(-not (Test-VerifiedRuntime $cacheWindows)){throw 'Linux runtime receipt or payload verification failed.'}
}
$destination=Join-Path $Output ('jniLibs\'+$lock.abi)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
foreach($name in $expected.Keys){Copy-Item -LiteralPath (Join-Path $cacheWindows ('jniLibs\'+$lock.abi+'\'+$name)) -Destination (Join-Path $destination $name) -Force}
Copy-Item -LiteralPath (Join-Path $cacheWindows 'native-build-receipt.json') -Destination (Join-Path $Output 'native-build-receipt.json') -Force
if(-not (Test-VerifiedRuntime $Output)){throw 'Runtime handoff bytes failed verification.'}
Write-Output ('Verified Linux PRoot artifacts copied to '+$Output)