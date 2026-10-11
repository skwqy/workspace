@echo off
rem Double-click wrapper: run startup-all.sh via Git Bash (chcp 65001 = UTF-8 output)
chcp 65001 >nul
"C:\Program Files\Git\bin\bash.exe" /d/code/workspace/docker-stacks/startup-all.sh
pause
