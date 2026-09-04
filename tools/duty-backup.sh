#!/bin/sh
#
# Резервне копіювання Duty NextGen: обидва змонтовані томи одним архівом.
#
# Бекапиться саме СТАН, а не контейнер: сам контейнер відтворюється з
# Dockerfile, а цінне лежить у томах — графіки з git-історією (/data) і
# облікові записи з реквізитами CalDAV (/config).
#
# Типово контейнер на час архівації зупиняється. Це кілька секунд простою,
# але воно того варте: застосунок зберігає графік у два кроки (запис файлу,
# потім git-коміт), і архів, знятий рівно між ними, дав би стан, якого
# ніколи не існувало. Для бекапу без простою — прапорець --live.
#
# Використання:
#   tools/duty-backup.sh [--live] [каталог-призначення]
#
# Змінні середовища (усі мають типові значення):
#   DUTY_CONTAINER   ім'я контейнера            (duty-nextgen)
#   DUTY_DATA_VOLUME хост-каталог тому /data    (/safe/duty-nextgen/data)
#   DUTY_CONF_VOLUME хост-каталог тому /config  (/safe/duty-nextgen/config)
#   DUTY_BACKUP_DIR  куди складати архіви       (/safe/duty-nextgen/backups)
#   DUTY_BACKUP_COMPRESSION  gz | bz2 | xz       (gz)

set -eu

CONTAINER="${DUTY_CONTAINER:-duty-nextgen}"
DATA_VOLUME="${DUTY_DATA_VOLUME:-/safe/duty-nextgen/data}"
CONF_VOLUME="${DUTY_CONF_VOLUME:-/safe/duty-nextgen/config}"
BACKUP_DIR="${DUTY_BACKUP_DIR:-/safe/duty-nextgen/backups}"
COMPRESSION="${DUTY_BACKUP_COMPRESSION:-gz}"

# gz — типово: найшвидший і всюди є. xz тисне помітно краще на текстових
# даних графіка, але й пакує довше; bz2 — проміжний варіант.
case "$COMPRESSION" in
    gz)  TAR_FLAG=z ;;
    bz2) TAR_FLAG=j ;;
    xz)  TAR_FLAG=J ;;
    *)   echo "Невідоме стиснення '$COMPRESSION' — очікую gz, bz2 чи xz." >&2; exit 1 ;;
esac

LIVE=0
if [ "${1:-}" = "--live" ]; then
    LIVE=1
    shift
fi
[ $# -gt 0 ] && BACKUP_DIR="$1"

for dir in "$DATA_VOLUME" "$CONF_VOLUME"; do
    if [ ! -d "$dir" ]; then
        echo "Немає каталогу $dir — перевір DUTY_DATA_VOLUME/DUTY_CONF_VOLUME." >&2
        exit 1
    fi
done

STAMP=$(date +%Y%m%d-%H%M%S)
ARCHIVE="$BACKUP_DIR/duty-nextgen-$STAMP.tar.$COMPRESSION"

mkdir -p "$BACKUP_DIR"
# Архів містить bcrypt-хеші паролів і пароль CalDAV — не для чужих очей.
chmod 700 "$BACKUP_DIR"

was_running=0
if [ "$LIVE" -eq 0 ] && command -v docker >/dev/null 2>&1; then
    if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" = "true" ]; then
        was_running=1
        echo "Зупиняю $CONTAINER на час архівації..."
        docker stop "$CONTAINER" >/dev/null
    fi
fi

# Запускаємо контейнер назад навіть якщо архівація впала на півдорозі —
# інакше невдалий бекап лишив би сервіс лежати.
restart_if_needed() {
    if [ "$was_running" -eq 1 ]; then
        echo "Запускаю $CONTAINER назад..."
        docker start "$CONTAINER" >/dev/null || true
        was_running=0
    fi
}
trap restart_if_needed EXIT INT TERM

echo "Архівую $DATA_VOLUME і $CONF_VOLUME -> $ARCHIVE"
# --numeric-owner: у контейнері застосунок працює від root, і власника
# файлів треба зберегти за uid/gid, а не за іменами з /etc/passwd
# машини, де архів розпаковуватимуть.
tar "c${TAR_FLAG}f" "$ARCHIVE" --numeric-owner \
    -C "$(dirname "$DATA_VOLUME")" "$(basename "$DATA_VOLUME")" \
    -C "$(dirname "$CONF_VOLUME")" "$(basename "$CONF_VOLUME")"
chmod 600 "$ARCHIVE"

restart_if_needed
trap - EXIT INT TERM

# Перевіряємо архів одразу: бекап, який не читається, гірший за
# відсутній — на нього розраховують.
echo "Перевіряю архів..."
tar tf "$ARCHIVE" >/dev/null

( cd "$BACKUP_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256" )

echo
echo "Готово: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
echo "Контрольна сума: $ARCHIVE.sha256"
echo "Відновлення:     tools/duty-restore.sh $ARCHIVE"
