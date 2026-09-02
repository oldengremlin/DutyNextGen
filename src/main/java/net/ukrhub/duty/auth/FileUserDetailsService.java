package net.ukrhub.duty.auth;

import net.ukrhub.duty.config.DutyProperties;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Облікові записи веб-автентифікації — окремий текстовий файл
 * {@code <config-dir>/users.txt} (рядок на користувача:
 * {@code ім'я:bcrypt-хеш}), навмисно поза git-історією графіка (це не дані
 * чергувань, а секрети). За замовчуванням файлу нема — жоден вхід
 * неможливий, доки адміністратор не створить першого користувача через
 * CLI (див. README.md, розділ «Первинна ініціалізація»).
 */
@Service
public class FileUserDetailsService implements UserDetailsService {

    private final Path usersFile;

    public FileUserDetailsService(DutyProperties properties) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String hash = UserStore.readUsers(usersFile).get(username);
        if (hash == null) {
            throw new UsernameNotFoundException("Користувача не знайдено: " + username);
        }
        return User.withUsername(username)
                .password(hash)
                .roles("USER")
                .build();
    }
}
