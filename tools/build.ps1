param([switch]$Install, [switch]$Qa)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path (Split-Path -Parent $project) '.android-tools/sdk' }
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$buildTools = Join-Path $sdk 'build-tools/35.0.0'
if (-not (Test-Path $androidJar)) { throw "Android SDK Platform 35 not found at $androidJar" }

$out = Join-Path $project 'build'
$gen = Join-Path $out 'gen'; $classes = Join-Path $out 'classes'; $dex = Join-Path $out 'dex'
foreach ($dir in @($gen, $classes, $dex)) {
    $resolved = [IO.Path]::GetFullPath($dir)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($out) + [IO.Path]::DirectorySeparatorChar)) { throw 'Unsafe build directory' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    New-Item $resolved -ItemType Directory -Force | Out-Null
}
$main = Join-Path $project 'app/src/main'; $manifest = Join-Path $main 'AndroidManifest.xml'
$package = if ($Qa) { 'com.compact.qa' } else { 'com.compact' }
$text = (Get-Content $manifest -Raw).Replace('package="com.compact"', ('package="' + $package + '"'))
if ($Qa) { $text = $text.Replace('<application ', '<application android:debuggable="true" ').Replace('android:label="Compact"', 'android:label="Compact QA"') }
if ($Qa) { $text = $text.Replace('</manifest>', '<instrumentation android:name="com.compact.QaChecks" android:targetPackage="com.compact.qa" /><instrumentation android:name="com.compact.QaDiag" android:targetPackage="com.compact.qa" /></manifest>') }
$manifestToUse = Join-Path $out 'AndroidManifest.xml'; Set-Content $manifestToUse $text -Encoding utf8
$aapt = Join-Path $buildTools 'aapt.exe'
$assets = Join-Path $out 'assets'
New-Item $assets -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $project 'libs/heifwriter-1.1.0/META-INF/androidx/heifwriter/heifwriter/LICENSE.txt') (Join-Path $assets 'heifwriter-LICENSE.txt') -Force
& $aapt package -f -m --custom-package com.compact -J $gen -M $manifestToUse -S (Join-Path $main 'res') -A $assets -I $androidJar -F (Join-Path $out 'base.apk')
if ($LASTEXITCODE -ne 0) { throw 'Resource packaging failed' }
$sources = @(Get-ChildItem (Join-Path $main 'java') -Recurse -Filter '*.java' | ForEach-Object FullName) + @(Get-ChildItem $gen -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($Qa) { $sources += @(Get-ChildItem (Join-Path $project 'app/src/qa') -Recurse -Filter '*.java' | ForEach-Object FullName) }
$heifJar = Join-Path $project 'libs/heifwriter-1.1.0/classes.jar'
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$androidJar;$heifJar" -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
$classFiles = @(Get-ChildItem $classes -Recurse -Filter '*.class' | ForEach-Object FullName)
& (Join-Path $buildTools 'd8.bat') --release --min-api 30 --lib $androidJar --output $dex $classFiles $heifJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
Copy-Item (Join-Path $out 'base.apk') (Join-Path $out 'unsigned.apk') -Force
Push-Location $dex; try { & $aapt add (Join-Path $out 'unsigned.apk') classes.dex | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'DEX packaging failed' } } finally { Pop-Location }
& (Join-Path $buildTools 'zipalign.exe') -f -p 4 (Join-Path $out 'unsigned.apk') (Join-Path $out 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }

$signing = Join-Path $project 'signing'; $key = Join-Path $signing 'compact-release.p12'; $passwordFile = Join-Path $signing 'password.dpapi.xml'
if (-not (Test-Path $key)) {
    New-Item $signing -ItemType Directory -Force | Out-Null
    $bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); $plain = [Convert]::ToBase64String($bytes)
    ConvertTo-SecureString $plain -AsPlainText -Force | Export-Clixml $passwordFile; $env:COMPACT_KEY_PASSWORD = $plain
    try { & keytool -genkeypair -keystore $key -storetype PKCS12 -alias compact -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=Compact, O=Private' -storepass:env COMPACT_KEY_PASSWORD -keypass:env COMPACT_KEY_PASSWORD; if ($LASTEXITCODE -ne 0) { throw 'Key creation failed' } } finally { Remove-Item Env:COMPACT_KEY_PASSWORD -ErrorAction SilentlyContinue }
}
$secure = Import-Clixml $passwordFile; $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $env:COMPACT_KEY_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer); $target = if ($Qa) { Join-Path $out 'compact-qa.apk' } else { New-Item (Join-Path $project 'releases') -ItemType Directory -Force | Out-Null; Join-Path $project 'releases/compact.apk' }; & (Join-Path $buildTools 'apksigner.bat') sign --ks $key --ks-type PKCS12 --ks-key-alias compact --ks-pass env:COMPACT_KEY_PASSWORD --key-pass env:COMPACT_KEY_PASSWORD --out $target (Join-Path $out 'aligned.apk'); if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' } } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); Remove-Item Env:COMPACT_KEY_PASSWORD -ErrorAction SilentlyContinue }
& (Join-Path $buildTools 'apksigner.bat') verify $target
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output $target
if ($Install) { & (Join-Path $sdk 'platform-tools/adb.exe') install -r $target; if ($LASTEXITCODE -ne 0) { throw 'Install failed' } }
