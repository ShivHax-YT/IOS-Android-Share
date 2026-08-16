$ErrorActionPreference = 'Stop'

$workspace = Split-Path -Parent $PSScriptRoot
$serviceId = 'com.nearpair.transfer.v1'
$expectedBonjour = '_EBD1B4122871._tcp'
$sha = [System.Security.Cryptography.SHA256]::Create()
$bytes = [System.Text.Encoding]::UTF8.GetBytes($serviceId)
$digest = ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
$actualBonjour = '_' + $digest.Substring(0, 12) + '._tcp'
if ($actualBonjour -ne $expectedBonjour) {
    throw "Bonjour mismatch: expected $expectedBonjour, calculated $actualBonjour"
}

$metadata = Get-Content -Raw (Join-Path $workspace 'protocol/fixtures/metadata.json') | ConvertFrom-Json
$verified = Get-Content -Raw (Join-Path $workspace 'protocol/fixtures/verified.json') | ConvertFrom-Json
$errorMessage = Get-Content -Raw (Join-Path $workspace 'protocol/fixtures/error.json') | ConvertFrom-Json
$schema = Get-Content -Raw (Join-Path $workspace 'protocol/transfer-v1.schema.json') | ConvertFrom-Json

if ($metadata.type -ne 'metadata' -or $metadata.version -ne 1) { throw 'Invalid metadata fixture envelope' }
if ($metadata.transferId -ne $verified.transferId -or $metadata.payloadId -ne $verified.payloadId) {
    throw 'Fixture acknowledgement does not correlate to metadata'
}
if ($metadata.transferId -ne $errorMessage.transferId -or $metadata.payloadId -ne $errorMessage.payloadId) {
    throw 'Fixture error message does not correlate to metadata'
}
if ($errorMessage.code -ne 'insufficientStorage') { throw 'Fixture error code is invalid' }
if ($metadata.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Fixture SHA-256 is invalid' }
if ($metadata.mimeType -notmatch '^(application/pdf|image/|video/)') { throw 'Fixture MIME type is outside v1' }
if ($schema.oneOf.Count -ne 3) { throw 'Schema must define metadata, verified, and error messages' }

$androidConstants = Get-Content -Raw (Join-Path $workspace 'android/app/src/main/java/com/nearpair/app/protocol/TransferProtocol.kt')
$iosConstants = Get-Content -Raw (Join-Path $workspace 'ios/NearPair/Protocol/TransferProtocol.swift')
$plist = Get-Content -Raw (Join-Path $workspace 'ios/NearPair/Info.plist')
foreach ($source in @($androidConstants, $iosConstants)) {
    if (-not $source.Contains($serviceId)) { throw 'A platform service ID drifted from the protocol' }
}
if (-not $plist.Contains($expectedBonjour)) { throw 'Info.plist Bonjour service drifted from the protocol' }

Write-Output "NearPair protocol v1 validation passed ($serviceId / $expectedBonjour)."
