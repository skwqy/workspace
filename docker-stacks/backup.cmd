@echo off
rem Double-click wrapper: run backup.sh via Git Bash (chcp 65001 = UTF-8 output)
chcp 65001 >nul
"C:\Program Files\Git\bin\bash.exe" /d/code/workspace/docker-stacks/backup.sh
pause
