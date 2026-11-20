param(
    [ValidateRange(1, 65535)]
    [int]$Port = 18128
)

$ErrorActionPreference = 'Stop'
$adminCredential = Get-Credential -Message '输入新的开发管理员账号与密码（至少6个字符）'
if ($null -eq $adminCredential) { return }
$previousAdminUsername = $env:DEV_ADMIN_USERNAME
$previousAdminPassword = $env:DEV_ADMIN_PASSWORD
$previousCookieSecure = $env:STUDENT_COOKIE_SECURE
Push-Location (Resolve-Path "$PSScriptRoot/..")
try {
    $env:DEV_ADMIN_USERNAME = $adminCredential.UserName
    $env:DEV_ADMIN_PASSWORD = $adminCredential.GetNetworkCredential().Password
    $env:STUDENT_COOKIE_SECURE = 'false'
    & ./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local,pgvector,community-admin-dev' "-Dspring-boot.run.arguments=--server.port=$Port --knowledge-base.rag.etl.enabled=false --spring.ai.vectorstore.pgvector.initialize-schema=false"
    if ($LASTEXITCODE -ne 0) { throw '开发管理员后端启动失败，请查看启动日志。' }
} finally {
    $env:DEV_ADMIN_USERNAME = $previousAdminUsername
    $env:DEV_ADMIN_PASSWORD = $previousAdminPassword
    $env:STUDENT_COOKIE_SECURE = $previousCookieSecure
    $adminCredential = $null
    Pop-Location
}
