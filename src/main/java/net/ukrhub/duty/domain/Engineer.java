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
