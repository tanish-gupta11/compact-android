param([switch]$Qa)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
$sdk=Join-Path (Split-Path -Parent $project) '.android-tools/sdk'
$bt=Join-Path $sdk 'build-tools/35.0.0'
$apk=if($Qa){Join-Path $project 'build/compact-qa.apk'}else{Join-Path $project 'releases/compact.apk'}
$expected=if($Qa){'com.compact.qa'}else{'com.compact'}
& (Join-Path $bt 'apksigner.bat') verify --verbose $apk
if($LASTEXITCODE -ne 0){throw 'Signature verification failed'}
$manifest=(& (Join-Path $bt 'aapt.exe') dump badging $apk) -join "`n"
if($LASTEXITCODE -ne 0){throw 'Manifest could not be inspected'}
if(-not $manifest.Contains("package: name='$expected'")){throw 'Wrong package'}
if(-not $manifest.Contains("launchable-activity: name='com.compact.MainActivity'")){throw 'Wrong launcher class'}
if(-not $manifest.Contains("sdkVersion:'30'") -or -not $manifest.Contains("targetSdkVersion:'35'")){throw 'Wrong Android SDK levels'}
if($manifest.Contains('android.permission.INTERNET')){throw 'Internet permission forbidden'}
if(-not $Qa -and $manifest.Contains('application-debuggable')){throw 'Release is debuggable'}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($apk)
try{
  $entry=$archive.GetEntry('classes.dex');if($null -eq $entry){throw 'Missing DEX'}
  $dex=Join-Path $project 'build/verify-classes.dex'
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$dex,$true)
  if($null -eq $archive.GetEntry('assets/heifwriter-LICENSE.txt')){throw 'Missing vendored license'}
}finally{$archive.Dispose()}
$classes=(& (Join-Path $bt 'dexdump.exe') $dex | Select-String 'Class descriptor') -join "`n"
foreach($name in @('Lcom/compact/MainActivity;','Lcom/compact/CompareActivity;','Lcom/compact/work/CompressService;','Landroidx/heifwriter/HeifWriter;')){
 if(-not $classes.Contains($name)){throw "Missing packaged class: $name"}
}
if(-not $Qa -and $classes.Contains('Lcom/compact/QaChecks;')){throw 'QA instrumentation leaked into release'}
Write-Output "PASS $expected signature, launcher, SDK 30/35, offline permissions, service/engine classes, license, release isolation"
Write-Output ("APK bytes: " + (Get-Item $apk).Length)
Write-Output ("SHA256: " + (Get-FileHash $apk -Algorithm SHA256).Hash)
