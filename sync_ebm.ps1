param(
    [string]$Source = 'D:\EBM_data\raw_parquet',
    [string]$Dest   = 'E:\임철훈\대학원\21. EBM\새 폴더',
    # 기본값: 실행 시점의 어제 (오늘 데이터는 아직 갱신 중이므로 제외)
    [datetime]$Cutoff = (Get-Date).Date.AddDays(-1),
    [switch]$DryRun,          # 복사 없이 대상 목록만 확인
    [switch]$ForceOverwrite   # 이미 있는 파일도 전부 덮어쓰기
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Source)) { throw "원본 경로가 없습니다: $Source" }
if (-not (Test-Path -LiteralPath $Dest))   { New-Item -ItemType Directory -Path $Dest | Out-Null }

$srcRoot = (Resolve-Path -LiteralPath $Source).Path.TrimEnd('\')
$dstRoot = (Resolve-Path -LiteralPath $Dest).Path.TrimEnd('\')
Write-Host "원본 : $srcRoot"
Write-Host "대상 : $dstRoot"
Write-Host ("기준 : {0:yyyy-MM-dd} 까지 (포함)" -f $Cutoff)

# 파일 날짜 판정: 경로에 YYYYMMDD / YYYY-MM-DD / YYYY_MM_DD 가 있으면 그 값, 없으면 수정일
$rx = [regex]'(20\d{2})[-_]?(0[1-9]|1[0-2])[-_]?(0[1-9]|[12]\d|3[01])'
function Get-FileDate($file, $rel) {
    $m = $rx.Match($rel)
    if ($m.Success) {
        try { return [datetime]::new([int]$m.Groups[1].Value, [int]$m.Groups[2].Value, [int]$m.Groups[3].Value) } catch {}
    }
    return $file.LastWriteTime.Date
}

$targets = foreach ($f in Get-ChildItem -LiteralPath $srcRoot -Recurse -File) {
    $rel = $f.FullName.Substring($srcRoot.Length).TrimStart('\')
    if ((Get-FileDate $f $rel) -le $Cutoff.Date) {
        [pscustomobject]@{ File = $f; Rel = $rel; Dst = (Join-Path $dstRoot $rel) }
    }
}
Write-Host "이동 대상(기준일 이하): $(@($targets).Count)개"

$log = New-Object System.Collections.Generic.List[object]
$copied = 0; $overwritten = 0; $skipped = 0

foreach ($t in $targets) {
    $f = $t.File
    $action = 'SKIP'
    if (-not (Test-Path -LiteralPath $t.Dst)) {
        $action = 'NEW'
    } else {
        $d = Get-Item -LiteralPath $t.Dst
        if ($ForceOverwrite -or $d.Length -ne $f.Length -or $d.LastWriteTime -ne $f.LastWriteTime) {
            $action = 'OVERWRITE'
        }
    }
    if ($action -ne 'SKIP' -and -not $DryRun) {
        New-Item -ItemType Directory -Path (Split-Path $t.Dst) -Force | Out-Null
        Copy-Item -LiteralPath $f.FullName -Destination $t.Dst -Force
        (Get-Item -LiteralPath $t.Dst).LastWriteTime = $f.LastWriteTime
    }
    switch ($action) { 'NEW' { $copied++ } 'OVERWRITE' { $overwritten++ } 'SKIP' { $skipped++ } }
    $log.Add([pscustomobject]@{ Action = $action; Path = $t.Rel; Bytes = $f.Length })
}

Write-Host "신규 $copied / 덮어쓰기 $overwritten / 이미 동일(건너뜀) $skipped"

if ($DryRun) { Write-Host '[DryRun] 실제 복사는 하지 않았습니다.'; $log | Export-Csv "$PSScriptRoot\sync_log.csv" -NoTypeInformation -Encoding UTF8; return }

# 검증: 대상 파일 전체의 존재 여부와 용량 비교
$missing = @(); $badSize = @(); $srcBytes = 0; $dstBytes = 0
foreach ($t in $targets) {
    $srcBytes += $t.File.Length
    if (-not (Test-Path -LiteralPath $t.Dst)) { $missing += $t.Rel; continue }
    $len = (Get-Item -LiteralPath $t.Dst).Length
    $dstBytes += $len
    if ($len -ne $t.File.Length) { $badSize += $t.Rel }
}
$cnt = @($targets).Count
Write-Host ("검증  개수: 원본 {0} / 대상 {1}" -f $cnt, ($cnt - $missing.Count))
Write-Host ("검증  용량: 원본 {0:N2} GB / 대상 {1:N2} GB" -f ($srcBytes/1GB), ($dstBytes/1GB))
if ($missing.Count -or $badSize.Count) {
    Write-Warning "불일치 발생! 누락 $($missing.Count)개, 용량 다름 $($badSize.Count)개"
    ($missing + $badSize) | Select-Object -First 20 | ForEach-Object { Write-Warning $_ }
} else {
    Write-Host '검증 OK: 개수와 용량이 모두 일치합니다.' -ForegroundColor Green
}
$log | Export-Csv "$PSScriptRoot\sync_log.csv" -NoTypeInformation -Encoding UTF8
