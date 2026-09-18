# short_link
短链项目


grafana-13.2.1导入方法
菜单找到serviceaccounts新建服务账户生成令牌
将json文件转换
powershell运行，文件目录自行设置
$dashboard = Get-Content -Raw -Encoding UTF8 -Path "D:\tools\grafana-13.2.1\dashbord.json" | ConvertFrom-Json
$body = @{ dashboard = $dashboard; overwrite = $true } | ConvertTo-Json -Depth 100

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText("D:\tools\grafana-13.2.1\wrapped.json", $body, $utf8NoBom)

Write-Host "✅ wrapped.json 已生成"

使用curl.exe导入
curl.exe -X POST http://localhost:3000/api/dashboards/db -H "Content-Type: application/json; charset=utf-8" -H "Authorization: Bearer <GRAFANA_SERVICE_ACCOUNT_TOKEN>" --data-binary "@D:\tools\grafana-13.2.1\wrapped.json"
