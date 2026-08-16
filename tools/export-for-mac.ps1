param(
    [string]$OutputPath = (Join-Path (Split-Path $PSScriptRoot -Parent) "NearPair-Mac-Transfer.zip")
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$stagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("NearPair-Mac-Transfer-" + [guid]::NewGuid().ToString("N"))
$bundleRoot = Join-Path $stagingRoot "NearPair"

try {
    New-Item -ItemType Directory -Path $bundleRoot | Out-Null

    Copy-Item -LiteralPath (Join-Path $projectRoot "README.md") -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $projectRoot "MIGRATE_TO_MAC.md") -Destination $bundleRoot
    Copy-Item -LiteralPath (Join-Path $projectRoot ".gitignore") -Destination $bundleRoot

    foreach ($directory in @("docs", "protocol", "ios", "tools")) {
        Copy-Item -LiteralPath (Join-Path $projectRoot $directory) -Destination $bundleRoot -Recurse
    }

    $androidDestination = Join-Path $bundleRoot "android"
    New-Item -ItemType Directory -Path $androidDestination | Out-Null
    foreach ($file in @("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat")) {
        Copy-Item -LiteralPath (Join-Path $projectRoot "android\$file") -Destination $androidDestination
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot "android\gradle") -Destination $androidDestination -Recurse
    Copy-Item -LiteralPath (Join-Path $projectRoot "android\app") -Destination $androidDestination -Recurse

    Get-ChildItem -LiteralPath $bundleRoot -Directory -Recurse -Force |
        Where-Object { $_.Name -in @("build", ".gradle", ".idea", "DerivedData", "xcuserdata", ".swiftpm") } |
        Sort-Object FullName -Descending |
        Remove-Item -Recurse -Force

    Get-ChildItem -LiteralPath $bundleRoot -File -Recurse -Force |
        Where-Object { $_.Name -in @("local.properties", ".DS_Store", "Thumbs.db") -or $_.Name -like "*.xcuserstate" } |
        Remove-Item -Force

    $manifestPath = Join-Path $bundleRoot "SHA256SUMS.txt"
    $manifestLines = Get-ChildItem -LiteralPath $bundleRoot -File -Recurse |
        Where-Object { $_.FullName -ne $manifestPath } |
        Sort-Object FullName |
        ForEach-Object {
            $relativePath = $_.FullName.Substring($bundleRoot.Length + 1).Replace("\", "/")
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relativePath"
        }
    Set-Content -LiteralPath $manifestPath -Value $manifestLines -Encoding utf8

    if (Test-Path -LiteralPath $OutputPath) {
        Remove-Item -LiteralPath $OutputPath -Force
    }
    Compress-Archive -LiteralPath $bundleRoot -DestinationPath $OutputPath -CompressionLevel Optimal
    $zipHash = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "Created: $OutputPath"
    Write-Output "SHA-256: $zipHash"
}
finally {
    if (Test-Path -LiteralPath $stagingRoot) {
        Remove-Item -LiteralPath $stagingRoot -Recurse -Force
    }
}
