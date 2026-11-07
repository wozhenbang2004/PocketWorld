#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Config = (Join-Path $PSScriptRoot '../../release.local.json'),
    [switch]$Plan,
    [switch]$Check,
    [switch]$PrepareOnly,
    [switch]$StageOnly
)
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$settings = Get-Content -Raw -LiteralPath $Config | ConvertFrom-Json
$arguments = @('-B', (Join-Path $PSScriptRoot 'release.py'), '--config', $Config)
if ($Plan) { $arguments += '--plan' }
if ($Check) { $arguments += '--check' }
if ($PrepareOnly) { $arguments += '--prepare-only' }
if ($StageOnly) { $arguments += '--stage-only' }
& $settings.python @arguments
exit $LASTEXITCODE
