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
