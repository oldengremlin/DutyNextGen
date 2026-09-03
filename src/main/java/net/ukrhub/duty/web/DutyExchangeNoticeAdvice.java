package net.ukrhub.duty.web;

import net.ukrhub.duty.auth.Role;
import net.ukrhub.duty.auth.RoleCheck;
import net.ukrhub.duty.auth.UserLinkService;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.exchange.DutyExchangeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Optional;

/**
 * Глобальний лічильник "скільки в обміні чергуваннями потребує моєї
 * уваги" — бейдж біля посилання на {@code /exchange} на кожній сторінці
 * (`schedule.html`), щоб не забути зайти перевірити. Рахує: пропозиції,
 * де я колега й ще не вирішив (PENDING), завершені пропозиції за моєю
 * участю, які я ще не "визнав" (Зрозуміло — вони й зникають з журналу),
 * і, окремо для адміністратора, скільки чекає на його затвердження.
 */
@ControllerAdvice
public class DutyExchangeNoticeAdvice {

    private final DutyExchangeService exchangeService;
    private final UserLinkService userLinkService;

    public DutyExchangeNoticeAdvice(DutyExchangeService exchangeService, UserLinkService userLinkService) {
        this.exchangeService = exchangeService;
        this.userLinkService = userLinkService;
    }

    @ModelAttribute("pendingExchangeCount")
    public int pendingExchangeCount(Authentication authentication) {
        if (authentication == null) {
            return 0;
        }
        int count = 0;
        if (RoleCheck.has(authentication, Role.ADMIN)) {
            count += exchangeService.pendingAdminApproval().size();
        }

        Optional<String> engineer = userLinkService.linkedEngineerOf(authentication.getName());
        if (engineer.isEmpty()) {
            return count;
        }
        for (DutyExchangeProposal proposal : exchangeService.proposalsFor(engineer.get())) {
            boolean awaitingMyDecision = proposal.status() == DutyExchangeStatus.PENDING
                    && proposal.counterpartName().equals(engineer.get());
            boolean unreadOutcome = !proposal.status().active();
            if (awaitingMyDecision || unreadOutcome) {
                count++;
            }
        }
        return count;
    }
}
