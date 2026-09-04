#!/bin/sh
#
# Відновлення Duty NextGen з архіву, знятого tools/duty-backup.sh.
#
# Відновлюються лише ТОМИ. Сам контейнер піднімається окремо (./dbuild) —
# він відтворюється з Dockerfile і бекапу не потребує.
#
# Приймає архів у будь-якому зі стиснень, які вміє duty-backup.sh
# (.tar.gz / .tar.bz2 / .tar.xz) — tar визначає його сам за вмістом.
#
# Використання:
#   tools/duty-restore.sh <архів.tar.{gz,bz2,xz}> [--force]
#
# Змінні середовища — ті самі, що й у duty-backup.sh:
#   DUTY_CONTAINER, DUTY_DATA_VOLUME, DUTY_CONF_VOLUME

set -eu

CONTAINER="${DUTY_CONTAINER:-duty-nextgen}"
DATA_VOLUME="${DUTY_DATA_VOLUME:-/safe/duty-nextgen/data}"
CONF_VOLUME="${DUTY_CONF_VOLUME:-/safe/duty-nextgen/config}"

ARCHIVE="${1:-}"
FORCE=0
[ "${2:-}" = "--force" ] && FORCE=1

if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
    echo "Використання: $0 <архів.tar.{gz,bz2,xz}> [--force]" >&2
    exit 1
fi

if [ -f "$ARCHIVE.sha256" ]; then
    echo "Перевіряю контрольну суму..."
    ( cd "$(dirname "$ARCHIVE")" && sha256sum -c "$(basename "$ARCHIVE").sha256" )
else
    echo "Файлу $ARCHIVE.sha256 нема — перевіряю лише цілісність архіву."
    tar tf "$ARCHIVE" >/dev/null
fi

DATA_PARENT="$(dirname "$DATA_VOLUME")"
CONF_PARENT="$(dirname "$CONF_VOLUME")"

if [ "$FORCE" -eq 0 ]; then
    echo
    echo "Буде ПЕРЕЗАПИСАНО:"
    echo "  $DATA_VOLUME"
    echo "  $CONF_VOLUME"
    printf "Продовжити? [y/N] "
    read -r answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "Скасовано."; exit 1 ;;
    esac
fi

if command -v docker >/dev/null 2>&1; then
    if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || echo false)" = "true" ]; then
        echo "Зупиняю $CONTAINER..."
        docker stop "$CONTAINER" >/dev/null
    fi
fi

# Наявні каталоги відсуваємо вбік, а не видаляємо: якщо архів виявиться
# не тим, буде куди повернутись. Прибрати їх — свідома дія людини потім.
STAMP=$(date +%Y%m%d-%H%M%S)
for dir in "$DATA_VOLUME" "$CONF_VOLUME"; do
    if [ -d "$dir" ]; then
        echo "Відсуваю наявний $dir -> $dir.before-restore-$STAMP"
        mv "$dir" "$dir.before-restore-$STAMP"
    fi
done

echo "Розпаковую $ARCHIVE..."
mkdir -p "$DATA_PARENT" "$CONF_PARENT"
# Архів зберігає імена каталогів верхнього рівня, тож розпаковуємо
# кожен у свого батька — так шляхи збігаються з тими, звідки знімали.
tar xf "$ARCHIVE" --numeric-owner -C "$DATA_PARENT" "$(basename "$DATA_VOLUME")"
if [ "$CONF_PARENT" != "$DATA_PARENT" ] || [ "$(basename "$CONF_VOLUME")" != "$(basename "$DATA_VOLUME")" ]; then
    tar xf "$ARCHIVE" --numeric-owner -C "$CONF_PARENT" "$(basename "$CONF_VOLUME")"
fi

echo "Перевіряю відновлені дані..."
if [ -d "$DATA_VOLUME/.git" ]; then
    git -C "$DATA_VOLUME" rev-parse --is-inside-work-tree >/dev/null \
        && echo "  git-журнал графіка читається"
fi
months=$(find "$DATA_VOLUME" -maxdepth 1 -type f -name '[0-9][0-9][0-9][0-9][0-9][0-9]' | wc -l)
echo "  місячних файлів графіка: $months"
[ -f "$CONF_VOLUME/users.txt" ] \
    && echo "  users.txt на місці ($(grep -cv '^#' "$CONF_VOLUME/users.txt" || true) записів)" \
    || echo "  УВАГА: users.txt не знайдено — увійти буде неможливо, доки не створиш адміністратора"

echo
echo "Томи відновлено. Запусти застосунок:  ./dbuild"
echo "Старі каталоги збережено як *.before-restore-$STAMP — прибери їх, коли переконаєшся, що все гаразд."
