# Construye el jar de PayAF y despliega el Worker con su contenedor en Cloudflare.
# Uso (desde la raíz del repo o desde worker/):  powershell -ExecutionPolicy Bypass -File worker\deploy.ps1
# El secreto PAYAF_DATABASE_URL se guarda aparte, una sola vez:  npx.cmd wrangler secret put PAYAF_DATABASE_URL
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    & .\mvnw.cmd -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Falló la compilación con Maven" }
    $jar = Get-ChildItem payaf-bootstrap\target\*.jar | Where-Object Name -notlike "*original*" | Select-Object -First 1
    Copy-Item $jar.FullName (Join-Path $PSScriptRoot "app.jar") -Force
} finally {
    Pop-Location
}

Push-Location $PSScriptRoot
try {
    & npx.cmd wrangler deploy
    if ($LASTEXITCODE -ne 0) { throw "Falló wrangler deploy" }
} finally {
    Pop-Location
}
