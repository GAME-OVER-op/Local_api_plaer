#!/system/bin/sh
ui_print "- api_plaer media-server"
ui_print "- Установка без монтирования в /system"

LIVE=/data/adb/modules/api_plaer

# сохраняем существующие настройки и список разрешённых при обновлении
for f in config.env allowed.json; do
  if [ -f "$LIVE/$f" ]; then
    cp -f "$LIVE/$f" "$MODPATH/$f"
  fi
done

# конфиг по умолчанию, если его ещё нет
if [ ! -f "$MODPATH/config.env" ]; then
  cat > "$MODPATH/config.env" <<EOF
export MEDIA_HOST=0.0.0.0
export MEDIA_PORT=10930
export MEDIA_ROOT=/storage
export MEDIA_NAME=media-server
export MEDIA_BACKGROUND=1
EOF
  ui_print "- Создан config.env (порт 10930, root /storage, фоновый режим)"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/media-server" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Бинарник, allowed.json и access.log будут в папке модуля"
ui_print "- Готово"
