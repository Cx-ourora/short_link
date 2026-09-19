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


接入Alertmanager，prometheus-webhook-dingtalk，prometheus和Grafana过程
prometheus配置对应服务位置，接入Alertmanager位置，以及告警规则yml文件
Grafana配置数据源为prometheus，配置相应json文件获取监控大屏数据
接入Alertmanager，配置短信模板，修改配置文件，接入对应邮箱地址以及prometheus-webhook-dingtalk
