# Flyway migrates before account initialization. First-time adoption of a non-empty schema is documented in docs/student-login.md.
$ErrorActionPreference = 'Stop'
$credential = Get-Credential -Message '输入开发学生账号，密码至少6个字符'
$previousUsername = $env:DEV_STUDENT_USERNAME
$previousPassword = $env:DEV_STUDENT_PASSWORD
$previousCookieSecure = $env:STUDENT_COOKIE_SECURE
Push-Location (Resolve-Path "$PSScriptRoot/..")
try {
    $env:DEV_STUDENT_USERNAME = $credential.UserName
    $env:DEV_STUDENT_PASSWORD = $credential.GetNetworkCredential().Password
    $env:STUDENT_COOKIE_SECURE = 'false'
    & ./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=local,pgvector,student-dev' '-Dspring-boot.run.arguments=--knowledge-base.rag.enabled=false'
} finally {
    $env:DEV_STUDENT_USERNAME = $previousUsername
    $env:DEV_STUDENT_PASSWORD = $previousPassword
    $env:STUDENT_COOKIE_SECURE = $previousCookieSecure
    Pop-Location
}
