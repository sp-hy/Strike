# Creates the release keystore and prints the GitHub Actions secret values.
# Run once, keep the .jks and passwords somewhere safe, then add the secrets to the repo.

param(
    [string]$OutFile = "strike-release.jks",
    [string]$Alias = "strike",
    [int]$ValidityDays = 10000
)

$ErrorActionPreference = "Stop"

if (Test-Path $OutFile) {
    Write-Error "Refusing to overwrite existing $OutFile"
}

$storePassword = Read-Host "Keystore password" -AsSecureString
$storePasswordConfirm = Read-Host "Confirm keystore password" -AsSecureString
$keyPassword = Read-Host "Key password (often same as keystore)" -AsSecureString

function Convert-Secure([SecureString]$value) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$storePw = Convert-Secure $storePassword
$storePw2 = Convert-Secure $storePasswordConfirm
$keyPw = Convert-Secure $keyPassword

if ($storePw -ne $storePw2) {
    Write-Error "Keystore passwords do not match"
}

Write-Host "Generating $OutFile ..."
keytool -genkeypair `
    -keystore $OutFile `
    -alias $Alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $ValidityDays `
    -storepass $storePw `
    -keypass $keyPw `
    -dname "CN=Strike, OU=Release, O=sp-hy, L=Unknown, ST=Unknown, C=AU"

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $OutFile)))

Write-Host ""
Write-Host "Add these repository secrets (Settings > Secrets and variables > Actions):"
Write-Host "  SIGNING_KEYSTORE_BASE64  = <paste below>"
Write-Host "  SIGNING_STORE_PASSWORD   = (the keystore password you entered)"
Write-Host "  SIGNING_KEY_ALIAS        = $Alias"
Write-Host "  SIGNING_KEY_PASSWORD     = (the key password you entered)"
Write-Host ""
Write-Host "SIGNING_KEYSTORE_BASE64 value:"
Write-Host $base64
Write-Host ""
Write-Host "Keep $OutFile and the passwords offline. Do not commit them."
