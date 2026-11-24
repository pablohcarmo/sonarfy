param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    $mavenArguments = @('package', '-Pcontainer')
    if ($SkipTests) { $mavenArguments += '-DskipTests' }
    & .\mvnw.cmd @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }
    $artifact = Join-Path $projectRoot 'target/container-build/sonarfy-0.0.1-SNAPSHOT.jar'
    $outputDirectory = Join-Path $projectRoot 'target/container'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $containerJar = Join-Path $outputDirectory 'app.jar'
    Copy-Item -LiteralPath $artifact -Destination $containerJar -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($containerJar)
    try {
        $configuration = @($archive.Entries | Where-Object {
            $_.FullName -match '^BOOT-INF/classes/application[^/]*\.(properties|ya?ml)$'
        })
        if ($configuration.Count -ne 0) { throw 'Container artifact contains application configuration.' }
    } finally {
        $archive.Dispose()
    }
    Write-Output "Container artifact prepared: $containerJar"
} finally {
    Pop-Location
}
