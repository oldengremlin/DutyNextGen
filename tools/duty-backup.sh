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

# Усе, що може завадити бекапу, перевіряємо ДО зупинки контейнера: інакше
# сервіс лягає заради операції, яка все одно впаде на першому ж файлі.
# Найчастіший випадок — запуск не від root: томи належать root (контейнер
# працює від нього), а users.txt має права 600.
unreadable=""
for dir in "$DATA_VOLUME" "$CONF_VOLUME"; do
    found=$(find "$dir" ! -readable -print -quit 2>/dev/null || true)
    [ -n "$found" ] && unreadable="$unreadable  $found\n"
done
if [ -n "$unreadable" ]; then
    printf 'Немає доступу на читання:\n' >&2
    printf "$unreadable" >&2
    echo "Томи належать root — запусти скрипт через sudo:" >&2
    echo "  sudo DUTY_BACKUP_DIR=$BACKUP_DIR DUTY_BACKUP_COMPRESSION=$COMPRESSION $0" >&2
    echo "(змінні саме ПІСЛЯ sudo — інакше він їх скине)" >&2
    exit 1
fi

if ! mkdir -p "$BACKUP_DIR" 2>/dev/null; then
    echo "Не вдалося створити каталог бекапів $BACKUP_DIR." >&2
    echo "Задай інший через DUTY_BACKUP_DIR або запусти через sudo:" >&2
    echo "  sudo DUTY_BACKUP_DIR=/шлях/до/бекапів $0" >&2
    exit 1
fi
if [ ! -w "$BACKUP_DIR" ]; then
    echo "Каталог $BACKUP_DIR недоступний для запису." >&2
    exit 1
fi

STAMP=$(date +%Y%m%d-%H%M%S)
ARCHIVE="$BACKUP_DIR/duty-nextgen-$STAMP.tar.$COMPRESSION"

# Архів містить bcrypt-хеші паролів і пароль CalDAV — не для чужих очей.
chmod 700 "$BACKUP_DIR" 2>/dev/null || true

was_running=0
if [ "$LIVE" -eq 0 ] && command -v docker >/dev/null 2>&1; then
    if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" = "true" ]; then
        was_running=1
        echo "Зупиняю $CONTAINER на час архівації..."
        docker stop "$CONTAINER" >/dev/null
    fi
fi

# Прибираємо за собою, хай би що пішло не так: контейнер має піднятись
# назад (інакше невдалий бекап лишає сервіс лежати), а недороблений архів
# — зникнути. Частковий архів небезпечніший за відсутній: він лежить у
# каталозі бекапів, важить правдоподібно й виглядає як робочий, а
# насправді в ньому бракує саме тих файлів, на яких tar спіткнувся.
cleanup() {
    status=$?
    if [ "$was_running" -eq 1 ]; then
        echo "Запускаю $CONTAINER назад..."
        docker start "$CONTAINER" >/dev/null || true
        was_running=0
    fi
    if [ "$status" -ne 0 ] && [ -n "${ARCHIVE:-}" ] && [ -f "$ARCHIVE" ]; then
        echo "Прибираю недороблений архів $ARCHIVE" >&2
        rm -f "$ARCHIVE" "$ARCHIVE.sha256"
    fi
    return $status
}
trap cleanup EXIT INT TERM

echo "Архівую $DATA_VOLUME і $CONF_VOLUME -> $ARCHIVE"
# --numeric-owner: у контейнері застосунок працює від root, і власника
# файлів треба зберегти за uid/gid, а не за іменами з /etc/passwd
# машини, де архів розпаковуватимуть.
tar "c${TAR_FLAG}f" "$ARCHIVE" --numeric-owner \
    -C "$(dirname "$DATA_VOLUME")" "$(basename "$DATA_VOLUME")" \
    -C "$(dirname "$CONF_VOLUME")" "$(basename "$CONF_VOLUME")"
chmod 600 "$ARCHIVE"

# Контейнер піднімаємо одразу після tar — далі йде лише перевірка архіву,
# і тримати сервіс лежачим заради неї нема потреби.
if [ "$was_running" -eq 1 ]; then
    echo "Запускаю $CONTAINER назад..."
    docker start "$CONTAINER" >/dev/null || true
    was_running=0
fi

# Перевіряємо архів одразу: бекап, який не читається, гірший за
# відсутній — на нього розраховують. Trap ще діє, тож биту перевірку
# архів не переживе.
echo "Перевіряю архів..."
tar tf "$ARCHIVE" >/dev/null

# Файли, які tar пропустив би через права, ми відсіяли до зупинки
# контейнера — але переконаймося, що ключові справді в архіві.
for must in users.txt; do
    if ! tar tf "$ARCHIVE" | grep -q "/$must\$"; then
        echo "В архіві нема $must — бекап неповний." >&2
        exit 1
    fi
done

( cd "$BACKUP_DIR" && sha256sum "$(basename "$ARCHIVE")" > "$(basename "$ARCHIVE").sha256" )

trap - EXIT INT TERM

echo
echo "Готово: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
echo "Контрольна сума: $ARCHIVE.sha256"
echo "Відновлення:     tools/duty-restore.sh $ARCHIVE"
