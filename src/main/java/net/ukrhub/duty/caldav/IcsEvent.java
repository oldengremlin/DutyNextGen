/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrhub.duty.caldav;

import java.time.LocalDate;

/**
 * Одна ICS-подія: детермінований UID (для ідемпотентності PUT),
 * дата (щоб {@link CalDavSyncService} ніколи не видаляв минуле) і
 * повне тіло {@code VCALENDAR}.
 */
public record IcsEvent(String uid, LocalDate date, String body) {
}
