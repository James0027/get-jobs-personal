@echo off
setlocal
echo 正在停止 Get Jobs ...
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8888,6866 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }"
timeout /t 2 >nul
powershell -NoProfile -Command "foreach($p in 8888,6866){ $c=Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue; if($c){ $p.ToString()+' 仍被占用' } else { $p.ToString()+' 已释放' } }"
echo.
pause
