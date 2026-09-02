package net.ukrhub.duty.git;

/**
 * Один запис історії змін файлу графіка — {@code хто/коли/що}.
 * {@code date} — рядок ISO 8601 як його віддає {@code git log --date=iso-strict}.
 */
public record CommitInfo(String hash, String author, String date, String message, String diff) {
}
