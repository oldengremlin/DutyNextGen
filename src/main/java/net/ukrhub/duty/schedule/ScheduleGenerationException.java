package net.ukrhub.duty.schedule;

/**
 * Не вдалося згенерувати графік наступного місяця — причина в повідомленні
 * (українською, придатна для показу адміністратору як є).
 */
public class ScheduleGenerationException extends RuntimeException {

    public ScheduleGenerationException(String message) {
        super(message);
    }
}
