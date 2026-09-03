package net.ukrhub.duty.auth;

import net.ukrhub.duty.config.DutyProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

/**
 * Переносить прив'язку "Користувача" до "Адміністратора" (інженера) при
 * перейменуванні П.І.Б. — викликається з {@code ScheduleEditController} після
 * успішного збереження графіка, якщо адміністратор змінив чиєсь ім'я.
 *
 * <p>Прив'язка зберігається за іменем (єдині наявні дані, які не треба
 * вигадувати заново), тож перейменування (напр. виправлення літери:
 * "Леонов" → "Лєонов") без цього кроку тихо розірвало б зв'язок. Історичні
 * місяці не враховуються навмисно — важливі лише поточні П.І.Б. та поточні
 * користувачі.
 */
@Service
public class UserLinkService {

    private final Path usersFile;

    public UserLinkService(DutyProperties properties) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
    }

    public void renameEngineer(String oldName, String newName) {
        if (oldName.equals(newName)) {
            return;
        }
        Map<String, UserStore.StoredUser> users = UserStore.readUsers(usersFile);
        for (var entry : users.entrySet()) {
            UserStore.StoredUser user = entry.getValue();
            if (oldName.equals(user.linkedEngineer())) {
                UserStore.writeUser(usersFile, entry.getKey(), user.passwordHash(), user.role(), newName);
            }
        }
    }
}
