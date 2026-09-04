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
package net.ukrhub.duty.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.io.IOException;

/**
 * Basic Auth на весь застосунок — жодної сторінки без входу. За
 * замовчуванням користувачів нема (файлу {@code users.txt} не існує), тож
 * усе повертає 401, доки адміністратор не створить першого користувача:
 * README.md, розділ «Первинна ініціалізація».
 *
 * <p>Обрано Basic, а не Digest: Digest у Spring Security фактично
 * застарілий (MD5, без сучасної підтримки), а Basic поверх HTTPS дає той
 * самий рівень захисту на практиці, простіше і краще підтримується.
 * Розгортати обов'язково за TLS (реверс-проксі) — Basic без HTTPS передає
 * пароль у base64, що еквівалентно відкритому тексту.
 *
 * <p>Три ролі — {@link Role}: перегляд ({@code VIEWER}) доступний будь-
 * якому автентифікованому користувачу; редагування позначок графіка —
 * {@code EDITOR}/{@code ADMIN}; керування ростером місяця (додати/
 * видалити адміністратора), генерація/видалення місячних графіків і
 * облікові записи ({@code /admin/**}) — лише {@code ADMIN}. Заборону
 * редагувати П.І.Б./тип у самій формі позначок (та сама POST-адреса, що
 * й позначки) URL-матчер не покриває — це перевіряється додатково в
 * {@code ScheduleEditController}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Content-Security-Policy. {@code 'unsafe-inline'} для скриптів і
     * стилів лишається свідомо: сторінки містять inline-обробники
     * ({@code onsubmit="return confirm(...)"} на кнопках видалення) та
     * inline-атрибути {@code style} — переписувати їх заради строгішої
     * політики тут нема сенсу, весь HTML однаково генерує Thymeleaf з
     * автоекрануванням, стороннього вводу в розмітку не потрапляє.
     * Цінність саме цього рядка — в решті директив: жодного зовнішнього
     * походження ({@code default-src 'self'}), заборонені плагіни
     * ({@code object-src 'none'}), підміна бази посилань
     * ({@code base-uri 'self'}), відправка форм назовні
     * ({@code form-action 'self'}) і вбудовування сторінки в чужий фрейм
     * ({@code frame-ancestors 'none'} — те саме, що вже дає
     * {@code X-Frame-Options: DENY} за замовчуванням, але в сучасному
     * вигляді).
     */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // HSTS вимикаємо навмисно: TLS завершується на
                        // реверс-проксі (nginx), і саме він — єдине місце,
                        // де цей заголовок має сенс задавати; застосунок
                        // за проксі бачить звичайний http і нав'язав би
                        // політику для всього домену від свого імені.
                        .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole(Role.ADMIN.springRole())
                        .requestMatchers("/schedule/*/generate-next/**").hasRole(Role.ADMIN.springRole())
                        .requestMatchers(HttpMethod.POST,
                                "/schedule/*/edit/add-engineer", "/schedule/*/edit/remove-engineer",
                                "/schedule/*/generate-next", "/schedule/*/delete",
                                "/exchange/*/approve", "/exchange/*/reject")
                        .hasRole(Role.ADMIN.springRole())
                        .requestMatchers("/schedule/*/edit").hasAnyRole(Role.EDITOR.springRole(), Role.ADMIN.springRole())
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(this::onLogoutSuccess));
        return http.build();
    }

    /**
     * HTTP Basic не має поняття "вийти" на рівні протоколу — обліковi дані
     * кешує сам браузер і надсилає повторно автоматично, доки вкладку не
     * закрито. Сервер може лише інвалідувати сесію (робить сам {@code
     * LogoutFilter} до виклику цього хендлера) і повернути 401 замість
     * редиректу на неіснуючу сторінку логіну — цього достатньо, щоб
     * розірвати збережений Spring-сесійний контекст, а сторінка пояснює
     * користувачу, чому одного натискання може бути не досить.
     */
    private void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                  Authentication authentication) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter().write("""
                <!DOCTYPE html>
                <html lang="uk"><head><meta charset="UTF-8"/><title>Вихід</title></head>
                <body style="font-family: sans-serif; max-width: 40rem; margin: 3rem auto; padding: 0 1rem;">
                <h1>Сесію застосунку завершено</h1>
                <p>Через особливості HTTP Basic-автентифікації браузер міг зберегти
                облікові дані й надіслати їх повторно автоматично при наступному
                переході. Якщо після цього все ще видно попереднього користувача —
                закрий усі вкладки цього сайту або відкрий приватне/інкогніто-вікно.</p>
                <p><a href="/">На головну</a></p>
                </body></html>
                """);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
