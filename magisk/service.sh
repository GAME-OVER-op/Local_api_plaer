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

# все данные (allowed.json, access.log, server_id) держим в папке модуля
export MEDIA_DATA_DIR="$MODDIR"

# По умолчанию показываем разрешённым клиентам понятное имя самого Android-устройства.
# MEDIA_NAME из config.env имеет приоритет.
if [ -z "$MEDIA_NAME" ]; then
  BRAND="$(getprop ro.product.manufacturer 2>/dev/null)"
  MODEL="$(getprop ro.product.model 2>/dev/null)"
  MEDIA_NAME="$(printf '%s %s' "$BRAND" "$MODEL" | sed 's/^ *//;s/ *$//;s/  */ /g')"
  [ -n "$MEDIA_NAME" ] || MEDIA_NAME="Android server"
fi
export MEDIA_NAME

chmod 0755 "$MODDIR/media-server" 2>/dev/null

# запуск в отдельной сессии, полностью в фоне
setsid "$MODDIR/media-server" >/dev/null 2>&1 </dev/null &
