$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $project 'build/test-classes'
New-Item $classes -ItemType Directory -Force | Out-Null
$root = Join-Path $project 'app/src/main/java/com/compact'
$sources = @('quality/Ssim.java','video/BitrateCalculator.java','video/Mp4TimePatcher.java','video/Location.java','photo/LosslessJpeg.java','work/JobState.java') | ForEach-Object { Join-Path $root $_ }
$sources += @(Get-ChildItem (Join-Path $project 'app/src/test') -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
& java -Xmx1g -classpath $classes com.compact.CoreTest
if ($LASTEXITCODE -ne 0) { throw 'Core regression tests failed' }
& java -Xmx1g -classpath $classes com.compact.AdversarialTest
if ($LASTEXITCODE -ne 0) { throw 'Adversarial tests failed' }
