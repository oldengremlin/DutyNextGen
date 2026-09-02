package net.ukrhub.duty.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

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

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole(Role.ADMIN.springRole())
                        .requestMatchers(HttpMethod.POST,
                                "/schedule/*/edit/add-engineer", "/schedule/*/edit/remove-engineer",
                                "/schedule/*/generate-next", "/schedule/*/delete")
                        .hasRole(Role.ADMIN.springRole())
                        .requestMatchers("/schedule/*/edit").hasAnyRole(Role.EDITOR.springRole(), Role.ADMIN.springRole())
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
