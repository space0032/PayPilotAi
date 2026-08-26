<#
.SYNOPSIS
    PayPilot AI — end-to-end demo script.
.DESCRIPTION
    Walks through the complete commerce flow against a running backend
    (default http://localhost:8080):

      1. Register a user
      2. Browse the catalog (trigram search)
      3. Add an item to cart
      4. Apply a discount offer
      5. Checkout → order created
      6. Initiate payment (mock gateway)
      7. Simulate Razorpay webhook capture
      8. View order history
      9. Start an agent session (mock planner)
     10. View agent transcript (tool-call audit trail)

    Prerequisites: backend running on :8080 (docker compose up -d && mvn spring-boot:run)
    Run from the repo root:  .\demo.ps1
#>

param(
    [string]$Base = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null,
        [string]$ContentType = "application/json"
    )
    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    $params = @{
        Method  = $Method
        Uri     = "$Base$Path"
        Headers = $headers
    }
    if ($Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 10)
        $params.ContentType = $ContentType
    }

    try {
        $resp = Invoke-RestMethod @params
        return $resp
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        Write-Host "  [$Method $Path] => $status" -ForegroundColor Yellow
        if ($status) {
            $reader = [System.IO.StreamReader]::new($_.Exception.Response.GetResponseStream())
            Write-Host "  $($reader.ReadToEnd())" -ForegroundColor DarkYellow
        }
        throw
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  PayPilot AI — End-to-End Demo" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------
# 1. Register
# ---------------------------------------------------------------
Write-Host "[1/10] Register a new user..." -ForegroundColor Green
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$reg = Invoke-Api POST "/api/v1/auth/register" @{
    email    = "demo$ts@paypilot.ai"
    password = "demo1234!"
}
$token = $reg.accessToken
Write-Host "  User registered. Token: $($token.Substring(0, 30))..." -ForegroundColor Gray

# ---------------------------------------------------------------
# 2. Browse catalog
# ---------------------------------------------------------------
Write-Host "[2/10] Searching catalog for 'running shoes'..." -ForegroundColor Green
$search = Invoke-Api GET "/api/v1/products?q=running+shoes&size=5" -Token $token
Write-Host "  Found $($search.totalElements) products. Top results:" -ForegroundColor Gray
$search.items | Select-Object -First 3 | ForEach-Object {
    Write-Host "    $($_.sku) | $($_.brand) $($_.title) | $($_.price) $($_.currency) | rating $($_.rating)" -ForegroundColor Gray
}
$firstProduct = $search.items[0]

# ---------------------------------------------------------------
# 3. Add to cart
# ---------------------------------------------------------------
Write-Host "[3/10] Adding $($firstProduct.sku) to cart..." -ForegroundColor Green
$cart = Invoke-Api POST "/api/v1/cart/items" @{
    productId = $firstProduct.id
    quantity  = 1
} -Token $token
Write-Host "  Cart total: $($cart.totalPaise / 100) paise ($($cart.totalPaise / 10000.0) INR)" -ForegroundColor Gray

# ---------------------------------------------------------------
# 4. Apply offer
# ---------------------------------------------------------------
Write-Host "[4/10] Applying offer code WELCOME10..." -ForegroundColor Green
$cart2 = Invoke-Api POST "/api/v1/cart/offers" @{ code = "WELCOME10" } -Token $token
Write-Host "  After discount: $($cart2.discountPaise / 10000.0) INR off" -ForegroundColor Gray
Write-Host "  New total: $($cart2.totalPaise / 10000.0) INR" -ForegroundColor Gray

# ---------------------------------------------------------------
# 5. Checkout
# ---------------------------------------------------------------
Write-Host "[5/10] Checking out..." -ForegroundColor Green
$order = Invoke-Api POST "/api/v1/orders" -Token $token
$orderId = $order.orderId
Write-Host "  Order #$orderId created | status=$($order.status) | total=$($order.total) $($order.currency)" -ForegroundColor Gray

# ---------------------------------------------------------------
# 6. Initiate payment
# ---------------------------------------------------------------
Write-Host "[6/10] Initiating payment..." -ForegroundColor Green
$payment = Invoke-Api POST "/api/v1/payments" @{ orderId = $orderId } -Token $token
$paymentId = $payment.paymentId
Write-Host "  Payment #$paymentId | status=$($payment.status) | razorpayOrderId=$($payment.razorpayOrderId)" -ForegroundColor Gray

# ---------------------------------------------------------------
# 7. Simulate webhook (capture)
# ---------------------------------------------------------------
Write-Host "[7/10] Simulating Razorpay webhook capture..." -ForegroundColor Green
Invoke-Api POST "/api/v1/payments/$paymentId/simulate-capture" -Token $token | Out-Null
Write-Host "  Payment captured. Checking order status..." -ForegroundColor Gray
$orderDetail = Invoke-Api GET "/api/v1/orders/$orderId" -Token $token
Write-Host "  Order #$orderId status: $($orderDetail.status)" -ForegroundColor Gray

# ---------------------------------------------------------------
# 8. View order history
# ---------------------------------------------------------------
Write-Host "[8/10] Fetching order history..." -ForegroundColor Green
$history = Invoke-Api GET "/api/v1/orders?page=0&size=5" -Token $token
Write-Host "  Total orders: $($history.totalElements)" -ForegroundColor Gray
$history.items | ForEach-Object {
    Write-Host "    #$($_.orderId) | $($_.status) | $($_.total) $($_.currency) | $($_.createdAt)" -ForegroundColor Gray
}

# ---------------------------------------------------------------
# 9. Agent session
# ---------------------------------------------------------------
Write-Host "[9/10] Starting agent session (mock planner)..." -ForegroundColor Green
$agent = Invoke-Api POST "/api/v1/agent/sessions" @{ goal = "Find me cheap running shoes under 5000 INR" } -Token $token
$sessionId = $agent.sessionId
Write-Host "  Session #$sessionId | status=$($agent.status) | consent=$($agent.consentState)" -ForegroundColor Gray

# ---------------------------------------------------------------
# 10. Agent transcript
# ---------------------------------------------------------------
Write-Host "[10/10] Fetching agent transcript (audit trail)..." -ForegroundColor Green
$transcript = Invoke-Api GET "/api/v1/agent/sessions/$sessionId" -Token $token
Write-Host "  Messages: $($transcript.messages.Count)" -ForegroundColor Gray
$transcript.messages | ForEach-Object {
    $preview = if ($_.content.Length -gt 80) { $_.content.Substring(0, 80) + "..." } else { $_.content }
    Write-Host "    [$($_.role)] $preview" -ForegroundColor DarkGray
}
if ($transcript.toolCalls) {
    Write-Host "  Tool calls:" -ForegroundColor Gray
    $transcript.toolCalls | ForEach-Object {
        Write-Host "    $($_.tool) | status=$($_.status) | ${$_.durationMs}ms" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  Demo complete!" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
