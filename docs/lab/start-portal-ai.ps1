# ============================================================================
# 从 .env 读取 Key 并启动 mall-portal（供验证 AI 能力用）
#
# 用法：  powershell -ExecutionPolicy Bypass -File C:\work\mall\docs\lab\start-portal-ai.ps1
#
# 设计要点：
#   1. 只读 .env 里的 DEEPSEEK_API_KEY，绝不打印 Key 内容（只显示长度与前缀）
#   2. 先杀掉占用 18087 的旧进程，避免「端口被占 → 新进程起不来 → 连到的还是旧服务」
#      这个坑非常隐蔽：脚本看起来跑通了，实际验证的是没有 Key 的旧实例
#   3. 等应用真正 Started 再返回，避免脚本抢跑
# ============================================================================

$ErrorActionPreference = "Stop"

$envFile = "C:\work\mall\.env"
if (-not (Test-Path $envFile)) {
    Write-Host "[FAIL] 找不到 $envFile" -ForegroundColor Red
    Write-Host "请先创建，内容形如：" -ForegroundColor Yellow
    Write-Host '  DEEPSEEK_API_KEY=sk-xxxxxxxx' -ForegroundColor Yellow
    exit 1
}

# ---- 解析 .env ----
# 兼容两种写法（都出现过）：
#   A. DEEPSEEK_API_KEY=sk-xxx        ← 标准写法
#   B. sk-xxx                         ← 整行只有值，没有变量名
# B 的成因：Set-Content -Value 'sk-xxx' 忘了带 KEY= 前缀。
# 这种文件对 Spring 毫无意义（没有变量名就没有绑定），但人肉看像是配好了，
# 所以这里兜住它，免得又出现「明明配了却不生效」。
$cfg = @{}
$rawLines = @()
Get-Content $envFile -Encoding UTF8 | ForEach-Object {
    # 去掉可能残留的 BOM（Set-Content -Encoding UTF8 在 PS5.1 下会写 BOM，
    # 读回来时第一行开头可能带 \ufeff，不处理会让前缀匹配失败）
    $line = $_.Trim().Trim([char]0xFEFF).Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $rawLines += $line
    $idx = $line.IndexOf("=")
    if ($idx -gt 0) {
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
        $cfg[$k] = $v
    }
}

$key = $cfg["DEEPSEEK_API_KEY"]
if ([string]::IsNullOrWhiteSpace($key)) {
    # 兜底：找整行就是一个 sk- 开头值的行
    foreach ($l in $rawLines) {
        $candidate = $l.Trim('"').Trim("'")
        if ($candidate -match '^sk-[A-Za-z0-9_\-]+$') {
            $key = $candidate
            Write-Host "[WARN] .env 里只有裸 Key 值，缺少 DEEPSEEK_API_KEY= 前缀。" -ForegroundColor Yellow
            Write-Host "       本次已按裸值处理；建议改成：DEEPSEEK_API_KEY=sk-xxx" -ForegroundColor Yellow
            break
        }
    }
}

if ([string]::IsNullOrWhiteSpace($key)) {
    Write-Host "[FAIL] .env 里找不到 Key（既没有 DEEPSEEK_API_KEY= 前缀，也不是裸 sk- 值）" -ForegroundColor Red
    exit 1
}
# 只显示长度和前缀，不泄露完整 Key
$prefixLen = [Math]::Min(7, $key.Length)
Write-Host ("[OK] 读到 Key: 长度={0} 前缀={1}..." -f $key.Length, $key.Substring(0, $prefixLen))

if ($key.Length -lt 20) {
    Write-Host "[WARN] Key 长度偏短，DeepSeek 的 Key 通常 30+ 字符，确认没写错" -ForegroundColor Yellow
}

$env:AI_CHAT_PROVIDER = "deepseek"
$env:DEEPSEEK_API_KEY = $key

# ---- 杀掉占用 18087 的旧进程 ----
$conns = Get-NetTCPConnection -LocalPort 18087 -State Listen -ErrorAction SilentlyContinue
if ($conns) {
    foreach ($c in $conns) {
        Write-Host ("[..] 杀掉占用 18087 的旧进程 PID={0}" -f $c.OwningProcess)
        Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
} else {
    Write-Host "[OK] 18087 空闲"
}

# ---- 启动 ----
$jar = "C:\work\mall\mall-portal\target\mall-portal-1.0-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    Write-Host "[FAIL] jar 不存在，先构建：mvn -pl mall-portal -am install -DskipTests -Ddocker.skip" -ForegroundColor Red
    exit 1
}

$log = "C:\work\logs-run\portal-ai-live.log"
$err = "C:\work\logs-run\portal-ai-live.err"
Write-Host "[..] 启动 mall-portal（日志：$log）"
$proc = Start-Process -FilePath "java" -ArgumentList @(
    "-Djava.io.tmpdir=C:\work\tmp-java",
    "-DLOG_FILE=C:\work\logs-app\mall-portal.log",
    "-jar", $jar,
    "--server.port=18087"
) -RedirectStandardOutput $log -RedirectStandardError $err -WindowStyle Hidden -PassThru

Write-Host ("[..] Java PID={0}，等待启动..." -f $proc.Id)
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    if (Select-String -Path $log -Pattern "Started MallPortalApplication" -Quiet -ErrorAction SilentlyContinue) {
        $ok = $true
        break
    }
    if ($proc.HasExited) { break }
}

if ($ok) {
    Write-Host "[OK] mall-portal 启动成功（带真实 Key）" -ForegroundColor Green
    Write-Host "     现在可以跑：python C:\work\mall\docs\lab\ai_agent_verify.py" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] 启动失败，日志尾部：" -ForegroundColor Red
    Get-Content $log -Tail 25
    exit 1
}
