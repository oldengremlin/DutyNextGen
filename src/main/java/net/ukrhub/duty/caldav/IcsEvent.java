package net.ukrhub.duty.caldav;

import java.time.LocalDate;

/**
 * Одна ICS-подія: детермінований UID (для ідемпотентності PUT),
 * дата (щоб {@link CalDavSyncService} ніколи не видаляв минуле) і
 * повне тіло {@code VCALENDAR}.
 */
public record IcsEvent(String uid, LocalDate date, String body) {
}
