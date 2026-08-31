# ============================================================
# DIGITAL BANK - START ALL SERVICES
# ============================================================
# Project:
#   BankingPayment-MicroService
#
# Purpose:
#   1. Start Docker infrastructure
#   2. Wait for infrastructure readiness
#   3. Start Spring Boot services sequentially
#   4. Apply Asia/Kolkata JVM timezone
#   5. Wait for Actuator health before continuing
#   6. Save each service output to logs/
#
# Run from project root:
#   .\scripts\start-all.ps1
# ============================================================

$ErrorActionPreference = "Stop"

# ------------------------------------------------------------
# PROJECT PATHS
# ------------------------------------------------------------

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$BackendRoot = Join-Path $ProjectRoot "backend"
$InfrastructureDir = Join-Path $BackendRoot "infrastructure"
$LogDir = Join-Path $ProjectRoot "logs"

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "        DIGITAL BANK - START ALL SERVICES" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ------------------------------------------------------------
# SERVICE DEFINITIONS
# ------------------------------------------------------------

$Services = @(
    @{
        Name = "Config Server"
        Key = "config-server"
        Directory = "Config-Server"
        Jar = "Config-Server-0.0.1-SNAPSHOT.jar"
        Port = 8888
        HealthUrl = "http://localhost:8888/actuator/health"
    },
    @{
        Name = "Service Registry"
        Key = "service-registry"
        Directory = "Service-Registry"
        Jar = "Service-Registry-0.0.1-SNAPSHOT.jar"
        Port = 8761
        HealthUrl = "http://localhost:8761/actuator/health"
    },
    @{
        Name = "API Gateway"
        Key = "api-gateway"
        Directory = "API-Gateway"
        Jar = "API-Gateway-0.0.1-SNAPSHOT.jar"
        Port = 8080
        HealthUrl = "http://localhost:8080/actuator/health"
    },
    @{
        Name = "Auth User"
        Key = "auth-user"
        Directory = "AuthUser-develop"
        Jar = "AuthUser-0.0.1-SNAPSHOT.jar"
        Port = 8094
        HealthUrl = "http://localhost:8094/actuator/health"
    },
    @{
        Name = "Customer Service"
        Key = "customer-service"
        Directory = "CustomerService-develop"
        Jar = "CustomerService-0.0.1-SNAPSHOT.jar"
        Port = 8083
        HealthUrl = "http://localhost:8083/actuator/health"
    },
    @{
        Name = "Account Service"
        Key = "account-service"
        Directory = "AccountService-develop"
        Jar = "AccountService-0.0.1-SNAPSHOT.jar"
        Port = 8084
        HealthUrl = "http://localhost:8084/actuator/health"
    },
    @{
        Name = "Biller Service"
        Key = "biller-service"
        Directory = "BillerService-develop"
       Jar = "BillPaymentService-0.0.1-SNAPSHOT.jar"
        Port = 8088
        HealthUrl = "http://localhost:8088/actuator/health"
    },
    @{
        Name = "Payment Orchestrator"
        Key = "payment-orchestrator"
        Directory = "PaymentOrchestrator-develop"
        Jar = "PaymentOrchestrator-0.0.1-SNAPSHOT.jar"
        Port = 8086
        HealthUrl = "http://localhost:8086/actuator/health"
    },
    @{
        Name = "BillPay Worker"
        Key = "billpay-worker-service"
        Directory = "BillPayWorkerService-develop"
        Jar = "BillPayWorkerService-0.0.1-SNAPSHOT.jar"
        Port = 8090
        HealthUrl = "http://localhost:8090/actuator/health"
    },
    @{
        Name = "Settlement Service"
        Key = "settlement-service"
        Directory = "SettlementService-develop"
       Jar = "settlement-service-0.0.1-SNAPSHOT.jar"
        Port = 8087
        HealthUrl = "http://localhost:8087/actuator/health"
    }
)

# ------------------------------------------------------------
# HELPER: TEST TCP PORT
# ------------------------------------------------------------

function Test-Port {
    param(
        [int]$Port
    )

    $connection = Get-NetTCPConnection `
        -LocalPort $Port `
        -State Listen `
        -ErrorAction SilentlyContinue

    return ($null -ne $connection)
}

# ------------------------------------------------------------
# HELPER: WAIT FOR HTTP HEALTH
# ------------------------------------------------------------

function Wait-ForHealth {
    param(
        [string]$Name,
        [string]$Url,
        [int]$TimeoutSeconds = 120
    )

    Write-Host ""
    Write-Host "Waiting for $Name..." -ForegroundColor Yellow

    $start = Get-Date

    while (((Get-Date) - $start).TotalSeconds -lt $TimeoutSeconds) {

        try {
            $response = Invoke-WebRequest `
                -Uri $Url `
                -UseBasicParsing `
                -TimeoutSec 5 `
                -ErrorAction Stop

            if ($response.StatusCode -eq 200) {
                Write-Host "$Name is READY." -ForegroundColor Green
                return $true
            }
        }
        catch {
            # Service is not ready yet.
        }

        Start-Sleep -Seconds 3
    }

    Write-Host "$Name did not become healthy within $TimeoutSeconds seconds." -ForegroundColor Red
    return $false
}

# ------------------------------------------------------------
# STEP 1: START DOCKER INFRASTRUCTURE
# ------------------------------------------------------------

Write-Host ""
Write-Host "[1/10] Starting Docker infrastructure..." -ForegroundColor Cyan

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker command was not found."
}

Push-Location $InfrastructureDir

try {
    docker compose up -d

    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed to start."
    }
}
finally {
    Pop-Location
}

Write-Host "Docker infrastructure started." -ForegroundColor Green

# ------------------------------------------------------------
# STEP 2: WAIT FOR POSTGRES
# ------------------------------------------------------------

Write-Host ""
Write-Host "[2/10] Checking PostgreSQL..." -ForegroundColor Cyan

$postgresReady = $false

for ($attempt = 1; $attempt -le 30; $attempt++) {

    try {
        docker exec digitalbank-postgres pg_isready `
            -U postgres `
            -d digitalbank | Out-Null

        if ($LASTEXITCODE -eq 0) {
            $postgresReady = $true
            break
        }
    }
    catch {
        # Continue waiting.
    }

    Write-Host "PostgreSQL not ready yet... attempt $attempt/30" -ForegroundColor Yellow
    Start-Sleep -Seconds 2
}

if (-not $postgresReady) {
    throw "PostgreSQL did not become ready."
}

Write-Host "PostgreSQL is READY." -ForegroundColor Green

# ------------------------------------------------------------
# STEP 3: CHECK REDIS
# ------------------------------------------------------------

Write-Host ""
Write-Host "[3/10] Checking Redis..." -ForegroundColor Cyan

if (-not (Test-Port 6379)) {
    Write-Host "Redis port 6379 is not listening yet." -ForegroundColor Yellow
    Start-Sleep -Seconds 3
}

if (Test-Port 6379) {
    Write-Host "Redis is READY." -ForegroundColor Green
}
else {
    throw "Redis did not become ready."
}

# ------------------------------------------------------------
# STEP 4: CHECK KAFKA
# ------------------------------------------------------------

Write-Host ""
Write-Host "[4/10] Checking Kafka..." -ForegroundColor Cyan

$kafkaReady = $false

for ($attempt = 1; $attempt -le 30; $attempt++) {

    if (Test-Port 9092) {
        $kafkaReady = $true
        break
    }

    Write-Host "Kafka not ready yet... attempt $attempt/30" -ForegroundColor Yellow
    Start-Sleep -Seconds 2
}

if (-not $kafkaReady) {
    throw "Kafka did not become ready."
}

Write-Host "Kafka is READY." -ForegroundColor Green

# ------------------------------------------------------------
# STEP 5: START SPRING BOOT SERVICES
# ------------------------------------------------------------

Write-Host ""
Write-Host "[5/10] Starting Spring Boot services..." -ForegroundColor Cyan

$startedProcesses = @()

foreach ($service in $Services) {

    Write-Host ""
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "Starting: $($service.Name)" -ForegroundColor Cyan
    Write-Host "Port:    $($service.Port)" -ForegroundColor Gray
    Write-Host "------------------------------------------------------------" -ForegroundColor DarkGray

    # Check if port is already occupied.
    if (Test-Port $service.Port) {
        Write-Host "Port $($service.Port) is already occupied." -ForegroundColor Red
        throw "$($service.Name) cannot start because port $($service.Port) is already in use."
    }

    $serviceDir = Join-Path $BackendRoot $service.Directory
    $jarPath = Join-Path $serviceDir "target\$($service.Jar)"
   

    if (-not (Test-Path $serviceDir)) {
        throw "Service directory not found: $serviceDir"
    }

    if (-not (Test-Path $jarPath)) {
        Write-Host "Executable JAR not found." -ForegroundColor Yellow
        Write-Host "Building $($service.Name)..." -ForegroundColor Yellow

        Push-Location $serviceDir

        try {
            mvn -DskipTests package

            if ($LASTEXITCODE -ne 0) {
                throw "Maven build failed for $($service.Name)."
            }
        }
        finally {
            Pop-Location
        }
    }

    if (-not (Test-Path $jarPath)) {
        throw "Executable JAR still not found: $jarPath"
    }

   # ------------------------------------------------------------
# LOG FILES
# ------------------------------------------------------------

$outLogPath = Join-Path $LogDir "$($service.Key).out.log"
$errLogPath = Join-Path $LogDir "$($service.Key).err.log"

# Clear previous logs.
if (Test-Path $outLogPath) {
    Remove-Item $outLogPath -Force
}

if (Test-Path $errLogPath) {
    Remove-Item $errLogPath -Force
}

# ------------------------------------------------------------
# START JAVA SERVICE
# ------------------------------------------------------------

$process = Start-Process `
    -FilePath "java.exe" `
    -ArgumentList @(
        "-Duser.timezone=Asia/Kolkata",
        "-jar",
        $jarPath
    ) `
    -WorkingDirectory $serviceDir `
    -RedirectStandardOutput $outLogPath `
    -RedirectStandardError $errLogPath `
    -PassThru

$startedProcesses += [PSCustomObject]@{
    Name = $service.Name
    PID = $process.Id
    Port = $service.Port
    OutLog = $outLogPath
    ErrLog = $errLogPath
}

    Write-Host "$($service.Name) started. PID: $($process.Id)" -ForegroundColor Green

    # Wait for health.
    $healthy = Wait-ForHealth `
        -Name $service.Name `
        -Url $service.HealthUrl `
        -TimeoutSeconds 120

    if (-not $healthy) {

        Write-Host ""
        Write-Host "========== FAILURE LOG ==========" -ForegroundColor Red

      if (Test-Path $errLogPath) {
    Write-Host ""
    Write-Host "--- STDERR ---" -ForegroundColor Red
    Get-Content $errLogPath -Tail 80
}

if (Test-Path $outLogPath) {
    Write-Host ""
    Write-Host "--- STDOUT ---" -ForegroundColor Yellow
    Get-Content $outLogPath -Tail 40
}

        Write-Host ""
        Write-Host "=================================" -ForegroundColor Red

        throw "$($service.Name) failed to become healthy."
    }
}

# ------------------------------------------------------------
# STEP 6: FINAL STATUS
# ------------------------------------------------------------

Write-Host ""
Write-Host "[6/10] All Java services started." -ForegroundColor Green

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "                 STARTUP COMPLETE" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green

Write-Host ""
Write-Host "SERVICE                    PORT       PID" -ForegroundColor Cyan
Write-Host "------------------------------------------------------------"

foreach ($item in $startedProcesses) {
    Write-Host ("{0,-25} {1,-10} {2}" -f `
        $item.Name,
        $item.Port,
        $item.PID)
}

Write-Host ""
Write-Host "Logs:" -ForegroundColor Cyan
Write-Host "  $LogDir"

Write-Host ""
Write-Host "Useful endpoints:" -ForegroundColor Cyan
Write-Host "  Eureka:       http://localhost:8761"
Write-Host "  API Gateway:  http://localhost:8080"
Write-Host "  Grafana:      http://localhost:3000"
Write-Host "  Prometheus:   http://localhost:9090"
Write-Host "  Zipkin:       http://localhost:9411"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green

