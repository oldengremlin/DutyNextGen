package net.ukrhub.duty.domain;

/**
 * Системний адміністратор у графіку.
 *
 * @param number       номер колонки (Adm_1, Adm_2, ...), стабільний ідентифікатор
 *                     у межах місячного файлу
 * @param name          ім'я, як воно показується в таблиці
 * @param onlyWorkdays  позначка "+" у застарілому форматі: людина працює лише
 *                      в будні (W) і ніколи не чергує (D) — не бере участі в ротації
 */
public record Engineer(int number, String name, boolean onlyWorkdays) {
}
