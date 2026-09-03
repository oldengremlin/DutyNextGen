FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Окремим шаром — щоб залежності кешувались, поки pom.xml не змінюється.
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
LABEL maintainer="Alexander Russkih <oldengremlin@gmail.com>"

# git потрібен як зовнішній процес для внутрішнього журналу змін
# графіка (GitCommitService, ProcessBuilder — не бібліотека).
RUN apt-get update && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

# C.UTF-8 — вбудована в glibc локаль, генерації не потребує.
# Обов'язково: без UTF-8-локалі кириличні автори/повідомлення git-комітів
# пошкоджуються при передачі через ProcessBuilder (sun.jnu.encoding) —
# застосунок навмисно падає на старті, якщо це не так.
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Без цього контейнер (і JVM разом з ним) типово в UTC — а "поточний
# місяць"/"сьогодні" для генерації графіка й CalDAV-синку (LocalDate.now(),
# YearMonth.now()) мають рахуватись за місцевим часом, не UTC.
ENV TZ="Europe/Kiev"

# Каталоги даних і конфігурації — лише точки монтування, у образ нічого
# не запікається. Навмисно БЕЗ вкладеної /data/duty — щоб хост-каталог,
# змонтований у /data, збігався з тим, що читає застосунок буквально
# один в один (на відміну від локального dev-дефолту ./data/duty, де
# вкладеність історично успадкована від структури репозиторію).
# Дивись README.md, розділ «Запуск у Docker».
ENV DUTY_DATA_DIR=/data
ENV DUTY_CONFIG_DIR=/config
# Шаблони ротації чергувань — git-версіюються так само, як і сам графік
# (RotationTemplateRepository використовує той самий GitCommitService),
# тому підкаталог уже змонтованого /data, а не /config: окремого тому
# не треба, ті самі git-коміти, що й для місячних файлів графіка.
ENV DUTY_TEMPLATES_DIR=/data/templates
# Кеш стану CalDAV-синку (опубліковані UID + хеші вмісту) — навмисно
# всередині вже змонтованого /config, а не окремий том: це лише
# внутрішній кеш (втрата — не катастрофа, найгірше кілька зайвих PUT'ів
# при наступному синку), але хочеться пережити перестворення контейнера.
ENV DUTY_CALDAV_STATE_DIR=/config/caldav-state
VOLUME ["/data", "/config"]

WORKDIR /app
COPY --from=build /build/target/duty-nextgen.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
