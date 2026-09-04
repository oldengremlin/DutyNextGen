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
package net.ukrhub.duty.git;

/**
 * Один запис історії змін файлу графіка — {@code хто/коли/що}.
 * {@code date} — рядок ISO 8601 як його віддає {@code git log --date=iso-strict}.
 */
public record CommitInfo(String hash, String author, String date, String message, String diff) {
}
