#!/system/bin/sh
# api_plaer media-server — фоновый автозапуск, без монтирования в /system
MODDIR=${0%/*}

# ждём полной загрузки системы
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done
sleep 3

# настройки (если есть): файл с export MEDIA_...
if [ -f "$MODDIR/config.env" ]; then
  . "$MODDIR/config.env"
fi

# все данные (allowed.json, access.log) держим в папке модуля
export MEDIA_DATA_DIR="$MODDIR"

chmod 0755 "$MODDIR/media-server" 2>/dev/null

# запуск в отдельной сессии, полностью в фоне
setsid "$MODDIR/media-server" >/dev/null 2>&1 </dev/null &
