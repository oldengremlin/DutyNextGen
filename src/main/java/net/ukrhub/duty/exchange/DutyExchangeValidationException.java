package net.ukrhub.duty.exchange;

/** Пропозиція обміну (чи крок у ній) порушує одне з правил обміну — повідомлення призначене для показу користувачу як є. */
public class DutyExchangeValidationException extends RuntimeException {

    public DutyExchangeValidationException(String message) {
        super(message);
    }
}
