$ErrorActionPreference = 'Stop'
$records = @(
  foreach ($item in @(
    @{ role = 'application'; path = $env:TESTHUB_SIGNED_APPLICATION },
    @{ role = 'installer'; path = $env:TESTHUB_SIGNED_INSTALLER }
  )) {
    $signature = Get-AuthenticodeSignature -LiteralPath $item.path
    $certificate = $signature.SignerCertificate
    $certificateHash = $null
    $codeSigning = $false
    if ($null -ne $certificate) {
      $sha = [System.Security.Cryptography.SHA256]::Create()
      try { $certificateHash = [BitConverter]::ToString($sha.ComputeHash($certificate.RawData)).Replace('-', '').ToLowerInvariant() }
      finally { $sha.Dispose() }
      $codeSigning = @($certificate.Extensions |
        Where-Object { $_ -is [System.Security.Cryptography.X509Certificates.X509EnhancedKeyUsageExtension] } |
        ForEach-Object { $_.EnhancedKeyUsages } |
        Where-Object { $_.Value -eq '1.3.6.1.5.5.7.3.3' }).Count -gt 0
    }
    [PSCustomObject]@{
      role = $item.role
      status = $signature.Status.ToString()
      signatureType = $signature.SignatureType.ToString()
      certificateSha256 = $certificateHash
      codeSigningEku = $codeSigning
      timestampPresent = $null -ne $signature.TimeStamperCertificate
    }
  }
)
ConvertTo-Json -InputObject $records -Depth 3 -Compress
