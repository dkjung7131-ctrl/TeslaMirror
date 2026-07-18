# TeslaMirror gateway desk probe — commercial-style CGNAT virtual IP
# PC must be on the phone hotspot. App: "VPN 프로브만" first.
#
# Usage: .\scripts\vpn-probe.ps1

param(
    [string]$FakeIp = "100.99.9.9",
    [string]$Gateway = ""
)

$ErrorActionPreference = "Continue"

function Send-Udp([string]$HostAddr, [int]$Port, [string]$Msg) {
    try {
        $udp = New-Object System.Net.Sockets.UdpClient
        $bytes = [Text.Encoding]::UTF8.GetBytes($Msg)
        $sent = $udp.Send($bytes, $bytes.Length, $HostAddr, $Port)
        $udp.Close()
        return "OK UDP $sent B → ${HostAddr}:${Port}"
    } catch {
        return "FAIL UDP ${HostAddr}:${Port} — $($_.Exception.Message)"
    }
}

Write-Host "=== TeslaMirror VPN desk probe (OWN_CGNAT) ===" -ForegroundColor Cyan

if (-not $Gateway) {
    $r = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Sort-Object RouteMetric | Select-Object -First 1
    $Gateway = $r.NextHop
}
Write-Host "Gateway (phone AP expected): $Gateway"

Write-Host "`n--- Find-NetRoute $FakeIp ---"
try { Find-NetRoute -RemoteIPAddress $FakeIp | Format-List } catch { Write-Host $_ }

Write-Host "`n--- Control: real AP IP ---" -ForegroundColor Green
if ($Gateway) {
    Write-Host (Send-Udp $Gateway 9997 "probe-C-ap")
    Write-Host (Send-Udp $Gateway 9999 "probe-A-via-ap")
}

Write-Host "`n--- Fake-IP UDP ---" -ForegroundColor Magenta
1..3 | ForEach-Object {
    Write-Host (Send-Udp $FakeIp 9999 "probe-fake-udp-$_")
    Write-Host (Send-Udp $FakeIp 9998 "probe-fake-bound-$_")
    Start-Sleep -Milliseconds 150
}

Write-Host "`n--- Fake-IP TCP HTTP (commercial MJPEG port 3333) ---" -ForegroundColor Magenta
try {
    $resp = Invoke-WebRequest -Uri "http://${FakeIp}:3333/" -UseBasicParsing -TimeoutSec 5
    Write-Host "HTTP $($resp.StatusCode) body=$($resp.Content.Trim())" -ForegroundColor Green
} catch {
    Write-Host "HTTP FAIL: $($_.Exception.Message)" -ForegroundColor Red
}

if ($Gateway) {
    Write-Host "`n--- Control TCP to real AP :3333 ---" -ForegroundColor Green
    try {
        $resp = Invoke-WebRequest -Uri "http://${Gateway}:3333/" -UseBasicParsing -TimeoutSec 5
        Write-Host "HTTP AP $($resp.StatusCode) body=$($resp.Content.Trim())" -ForegroundColor Green
    } catch {
        Write-Host "HTTP AP FAIL: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host @"

=== logcat: adb logcat -s GatewayVpn:I ===
  PASS:  TCP RECV[...] SUCCESS_TCP  or  PROBE RECV[B-fakeip]  or  SUCCESS_TUN_INGRESS
  CTRL:  PROBE RECV[C-ap] / HTTP to real AP
  FAIL:  heartbeat tunRx=0 tcp=0 after probes
"@
