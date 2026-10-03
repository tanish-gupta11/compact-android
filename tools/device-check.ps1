param([switch]$Install)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$adb=Join-Path (Split-Path -Parent $project) '.android-tools/sdk/platform-tools/adb.exe'
$devices=@(& $adb devices | Select-String '^\S+\s+device$')
if($devices.Count -ne 1){throw 'Connect exactly one authorized Android test device first.'}
if($Install){& $adb install -r (Join-Path $project 'build/compact-qa.apk');if($LASTEXITCODE -ne 0){throw 'QA install failed'}}
# This instrumentation only generates private CompactTest_* fixtures; no media permission grants.
$result = & $adb shell am instrument -w com.compact.qa/com.compact.QaChecks
if($LASTEXITCODE -ne 0){throw 'Instrumentation command failed'}
Write-Output $result
if(-not (($result -join "`n") -match 'INSTRUMENTATION_RESULT: result=PASS')){throw 'Native checks failed; inspect instrumentation output'}
Write-Output 'Read the instrumentation result: SKIPPED engine cases are not passes. This is not the full device acceptance suite.'
