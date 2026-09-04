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

import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.template.RotationTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Керування шаблонами ротації чергувань — лише ADMIN (обмежено
 * {@code /admin/**} у {@code SecurityConfig}). CRUD-редактор: список із
 * наочним прев'ю кожного шаблону, створення (спершу питаємо кількість
 * слотів — {@code K} фіксується один раз і далі не змінюється, — потім
 * грід під цю кількість), редагування довжини періоду й самих позначок.
 *
 * <p>Сам механізм застосування шаблону до генерації графіка (заміна
 * вбудованого в {@code DutyScheduleGenerator} патерну, мапінг слот →
 * адміністратор) — окрема задача, свідомо не чіпається тут: цей
 * контролер лише зберігає шаблони, придатні для майбутнього
 * використання.
 */
@Controller
@RequestMapping("/admin/templates")
public class RotationTemplateController {

    private static final int MIN_SLOTS = 2;
    private static final int MAX_SLOTS = 12;

    private final RotationTemplateRepository repository;

    public RotationTemplateController(RotationTemplateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", repository.findAll());
        return "templates-list";
    }

    @GetMapping("/new")
    public String newForm() {
        return "template-new";
    }

    @PostMapping
    public String create(@RequestParam int slots, Principal principal) {
        if (slots < MIN_SLOTS || slots > MAX_SLOTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Кількість чергових має бути від " + MIN_SLOTS + " до " + MAX_SLOTS);
        }
        int id = repository.nextId();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            rows.add("-");
        }
        persist(id, "Новий шаблон", rows, "Створено шаблон №" + id + " на " + slots + " чергових", principal);
        return "redirect:/admin/templates/" + id + "/edit";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable int id, Model model) {
        RotationTemplate template = require(id);
        model.addAttribute("template", template);
        model.addAttribute("days", range(template.period()));
        model.addAttribute("slotsRange", range(template.slots()));
        return "template-edit";
    }

    @PostMapping("/{id}/edit")
    public String save(@PathVariable int id, @RequestParam String name,
                        @RequestParam Map<String, String> allParams, Principal principal) {
        RotationTemplate existing = require(id);
        List<String> rows = new ArrayList<>();
        for (int slot = 1; slot <= existing.slots(); slot++) {
            StringBuilder row = new StringBuilder();
            for (int day = 1; day <= existing.period(); day++) {
                row.append(normalize(allParams.get("mark_" + slot + "_" + day)));
            }
            rows.add(row.toString());
        }
        String newName = name.isBlank() ? existing.name() : name.strip();
        persist(id, newName, rows, "Змінено шаблон №" + id, principal);
        return "redirect:/admin/templates/" + id + "/edit";
    }

    @PostMapping("/{id}/add-day")
    public String addDay(@PathVariable int id, Principal principal) {
        RotationTemplate existing = require(id);
        List<String> rows = existing.rows().stream().map(row -> row + "-").toList();
        persist(id, existing.name(), rows, "Додано день до шаблону №" + id, principal);
        return "redirect:/admin/templates/" + id + "/edit";
    }

    @PostMapping("/{id}/remove-day")
    public String removeDay(@PathVariable int id, Principal principal) {
        RotationTemplate existing = require(id);
        if (existing.period() <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У шаблоні має лишатись хоча б один день");
        }
        List<String> rows = existing.rows().stream().map(row -> row.substring(0, row.length() - 1)).toList();
        persist(id, existing.name(), rows, "Прибрано день з шаблону №" + id, principal);
        return "redirect:/admin/templates/" + id + "/edit";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable int id, Principal principal) {
        require(id);
        String username = username(principal);
        repository.delete(id, "Видалено шаблон №" + id + " (" + username + ")", username, username + "@duty.local");
        return "redirect:/admin/templates";
    }

    /** Позначка поза D/W мовчки стає "-" (вихідний) — той самий підхід, що й {@code DutyMark.fromChar}. */
    private static char normalize(String value) {
        char raw = (value == null || value.isBlank()) ? '-' : value.charAt(0);
        return raw == 'D' || raw == 'W' ? raw : '-';
    }

    private void persist(int id, String name, List<String> rows, String message, Principal principal) {
        String username = username(principal);
        RotationTemplate template = new RotationTemplate(id, name, rows);
        repository.save(template, message + " (" + username + ")", username, username + "@duty.local");
    }

    private static String username(Principal principal) {
        return principal != null ? principal.getName() : "невідомий";
    }

    private RotationTemplate require(int id) {
        return repository.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає шаблону №" + id));
    }

    private static List<Integer> range(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }
}
