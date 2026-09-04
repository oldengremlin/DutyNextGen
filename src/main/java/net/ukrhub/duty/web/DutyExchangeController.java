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
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.exchange.DutyExchangeDraftStore;
import net.ukrhub.duty.exchange.DutyExchangeDraftStore.DraftStep;
import net.ukrhub.duty.exchange.DutyExchangeService;
import net.ukrhub.duty.exchange.DutyExchangeValidationException;
import net.ukrhub.duty.exchange.DutySwappableDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Обмін чергуваннями між черговими інженерами — {@code /exchange}. Доступ:
 * {@link Role#ADMIN} чи будь-хто, чий обліковий запис прив'язаний
 * ({@code UserLinkService}) до чергового інженера (не "лише робочі
 * дні") — саме цей зв'язок і визначає "чий" графік показувати. Адмін, що
 * теж прив'язаний до інженера, отримує обидва набори дій одразу.
 *
 * <p>Конструктор пропозиції — без JS: колега обирається через
 * {@code GET ?counterpart=...} (звичайний select+submit), пара дат
 * додається в чернетку ({@link DutyExchangeDraftStore}) окремим POST, і
 * лише фінальний "Надіслати" ділить чернетку на пропозиції per-колега
 * та створює їх ({@link DutyExchangeService#propose}).
 */
@Controller
@RequestMapping("/exchange")
public class DutyExchangeController {

    private static final Logger log = LoggerFactory.getLogger(DutyExchangeController.class);

    /**
     * {@code GitCommitService} зрідка (спостережено в production, не
     * відтворено локально) не може застосувати git-коміт через
     * транзиентний збій спавну зовнішнього процесу — уже з ретраями
     * всередині {@code GitCommitService}. Тут — останній рубіж: замість
     * Whitelabel Error Page користувач бачить те саме дружнє
     * повідомлення про помилку, що й для звичайних валідаційних
     * відмов.
     */
    private static final String GIT_FAILURE_MESSAGE =
            "Не вдалося зберегти зміни через тимчасовий збій. Спробуйте, будь ласка, ще раз за кілька секунд.";

    private final DutyExchangeService exchangeService;
    private final DutyExchangeDraftStore draftStore;
    private final UserLinkService userLinkService;

    public DutyExchangeController(DutyExchangeService exchangeService, DutyExchangeDraftStore draftStore,
                                   UserLinkService userLinkService) {
        this.exchangeService = exchangeService;
        this.draftStore = draftStore;
        this.userLinkService = userLinkService;
    }

    @GetMapping
    public String view(@RequestParam(required = false) String counterpart, Model model, Authentication authentication) {
        boolean isAdmin = RoleCheck.has(authentication, Role.ADMIN);
        List<String> engineers = exchangeService.rotationEngineerNames();
        String myEngineer = linkedRotationEngineer(authentication, engineers).orElse(null);

        if (!isAdmin && myEngineer == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Обмін чергуваннями доступний лише черговим інженерам і адміністратору");
        }

        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("myEngineer", myEngineer);

        if (myEngineer != null) {
            model.addAttribute("myDates", exchangeService.datesFor(myEngineer));
            model.addAttribute("myProposals", exchangeService.proposalsFor(myEngineer));
            model.addAttribute("draft", draftStore.get(authentication.getName()));

            List<String> otherEngineers = engineers.stream().filter(e -> !e.equals(myEngineer)).toList();
            model.addAttribute("otherEngineers", otherEngineers);

            String selected = counterpart != null && otherEngineers.contains(counterpart) ? counterpart : null;
            model.addAttribute("counterpart", selected);
            if (selected != null) {
                model.addAttribute("counterpartDates", exchangeService.datesFor(selected));
            }
        }
        if (isAdmin) {
            model.addAttribute("pendingApproval", exchangeService.pendingAdminApproval());
        }
        return "exchange";
    }

    @PostMapping("/draft/add")
    public String addDraftStep(@RequestParam String counterpart, @RequestParam LocalDate myDate,
                                @RequestParam LocalDate theirDate, Authentication authentication,
                                RedirectAttributes redirectAttributes) {
        Optional<String> myEngineer = linkedRotationEngineer(authentication, exchangeService.rotationEngineerNames());
        if (myEngineer.isEmpty()) {
            redirectAttributes.addFlashAttribute("exchangeError", "Немає прив'язки до чергового інженера");
            return "redirect:/exchange";
        }

        Optional<DutyMark> type = exchangeService.datesFor(myEngineer.get()).stream()
                .filter(d -> d.date().equals(myDate))
                .map(DutySwappableDate::type)
                .findFirst();
        if (type.isEmpty()) {
            redirectAttributes.addFlashAttribute("exchangeError", myDate + " — не ваш день чергування чи роботи");
            return redirectToCounterpart(counterpart);
        }

        draftStore.add(authentication.getName(), new DraftStep(counterpart, new DutyExchangeStep(type.get(), myDate, theirDate)));
        return redirectToCounterpart(counterpart);
    }

    /**
     * П.І.Б. колеги в query string — обов'язково URL-кодоване: кирилиця й
     * пробіл у сирому вигляді в {@code Location} — невалідний HTTP-заголовок,
     * Tomcat його просто відкидає (302 без Location — порожній екран у
     * браузері, дію при цьому вже виконано).
     */
    private String redirectToCounterpart(String counterpart) {
        return "redirect:/exchange?counterpart=" + URLEncoder.encode(counterpart, StandardCharsets.UTF_8);
    }

    @PostMapping("/draft/remove")
    public String removeDraftStep(@RequestParam int index, Authentication authentication) {
        draftStore.removeAt(authentication.getName(), index);
        return "redirect:/exchange";
    }

    @PostMapping("/draft/submit")
    public String submitDraft(Authentication authentication, RedirectAttributes redirectAttributes) {
        String username = authentication.getName();
        Optional<String> myEngineer = linkedRotationEngineer(authentication, exchangeService.rotationEngineerNames());
        if (myEngineer.isEmpty()) {
            redirectAttributes.addFlashAttribute("exchangeError", "Немає прив'язки до чергового інженера");
            return "redirect:/exchange";
        }

        List<DraftStep> draft = draftStore.get(username);
        if (draft.isEmpty()) {
            redirectAttributes.addFlashAttribute("exchangeError", "Спершу додайте хоч один крок обміну в чернетку");
            return "redirect:/exchange";
        }

        Map<String, List<DutyExchangeStep>> byCounterpart = draft.stream()
                .collect(Collectors.groupingBy(DraftStep::counterpartName, LinkedHashMap::new,
                        Collectors.mapping(DraftStep::step, Collectors.toList())));

        try {
            for (var entry : byCounterpart.entrySet()) {
                exchangeService.propose(myEngineer.get(), username, entry.getKey(), entry.getValue());
            }
            draftStore.clear(username);
        } catch (DutyExchangeValidationException e) {
            redirectAttributes.addFlashAttribute("exchangeError", e.getMessage());
        } catch (UncheckedIOException | IllegalStateException e) {
            log.warn("Не вдалося застосувати git-коміт для пропозиції обміну", e);
            redirectAttributes.addFlashAttribute("exchangeError", GIT_FAILURE_MESSAGE);
        }
        return "redirect:/exchange";
    }

    @PostMapping("/{id}/accept")
    public String accept(@PathVariable int id, Authentication authentication, RedirectAttributes redirectAttributes) {
        return actAsParty(id, authentication, redirectAttributes,
                (proposalId, username, engineer) -> exchangeService.accept(proposalId, username, engineer));
    }

    @PostMapping("/{id}/decline")
    public String decline(@PathVariable int id, Authentication authentication, RedirectAttributes redirectAttributes) {
        return actAsParty(id, authentication, redirectAttributes,
                (proposalId, username, engineer) -> exchangeService.decline(proposalId, username, engineer));
    }

    @PostMapping("/{id}/ack")
    public String acknowledge(@PathVariable int id, Authentication authentication, RedirectAttributes redirectAttributes) {
        return actAsParty(id, authentication, redirectAttributes,
                (proposalId, username, engineer) -> {
                    exchangeService.acknowledge(proposalId, username, engineer);
                    return null;
                });
    }

    /** {@code approve}/{@code reject} — лише ADMIN, гарантовано {@link net.ukrhub.duty.config.SecurityConfig}. */
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable int id, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            exchangeService.approve(id, authentication.getName());
        } catch (DutyExchangeValidationException e) {
            redirectAttributes.addFlashAttribute("exchangeError", e.getMessage());
        } catch (UncheckedIOException | IllegalStateException e) {
            log.warn("Не вдалося застосувати git-коміт для затвердження обміну #{}", id, e);
            redirectAttributes.addFlashAttribute("exchangeError", GIT_FAILURE_MESSAGE);
        }
        return "redirect:/exchange";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable int id, Authentication authentication, RedirectAttributes redirectAttributes) {
        try {
            exchangeService.reject(id, authentication.getName());
        } catch (DutyExchangeValidationException e) {
            redirectAttributes.addFlashAttribute("exchangeError", e.getMessage());
        } catch (UncheckedIOException | IllegalStateException e) {
            log.warn("Не вдалося застосувати git-коміт для відхилення обміну #{}", id, e);
            redirectAttributes.addFlashAttribute("exchangeError", GIT_FAILURE_MESSAGE);
        }
        return "redirect:/exchange";
    }

    private interface PartyAction {
        Object apply(int proposalId, String username, String engineerName);
    }

    private String actAsParty(int id, Authentication authentication, RedirectAttributes redirectAttributes, PartyAction action) {
        Optional<String> myEngineer = userLinkService.linkedEngineerOf(authentication.getName());
        if (myEngineer.isEmpty()) {
            redirectAttributes.addFlashAttribute("exchangeError", "Немає прив'язки до чергового інженера");
            return "redirect:/exchange";
        }
        try {
            action.apply(id, authentication.getName(), myEngineer.get());
        } catch (DutyExchangeValidationException e) {
            redirectAttributes.addFlashAttribute("exchangeError", e.getMessage());
        } catch (UncheckedIOException | IllegalStateException e) {
            log.warn("Не вдалося застосувати git-коміт для дії над пропозицією обміну #{}", id, e);
            redirectAttributes.addFlashAttribute("exchangeError", GIT_FAILURE_MESSAGE);
        }
        return "redirect:/exchange";
    }

    private Optional<String> linkedRotationEngineer(Authentication authentication, List<String> rotationEngineerNames) {
        return userLinkService.linkedEngineerOf(authentication.getName()).filter(rotationEngineerNames::contains);
    }
}
