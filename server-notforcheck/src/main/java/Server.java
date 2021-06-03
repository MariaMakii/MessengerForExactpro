import io.javalin.Javalin;
import io.javalin.core.JavalinConfig;

import java.util.*;

public class Server {

    private final Javalin APP;
    private static final DataBase DATABASE = new DataBase();

    ChatManager chatManager = new ChatManager();
    MessageManager chatStorage = new MessageManager();
    SseConnectionsManager sseConnectionsManager = new SseConnectionsManager();
    Users users = new Users();

    public static DataBase getDatabase() {
        return DATABASE;
    }

    public Server() {
        APP = Javalin.create(JavalinConfig::enableCorsForAllOrigins).start(8081);
    }

    /**
     * Получение строки-идентификатора
     */
    public static String getIdString() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public void start() {
        DATABASE.open();

        APP.get("open-chat", chatManager::openChat);

        APP.get("login", sseConnectionsManager::login); //

        APP.post("registration", sseConnectionsManager::registerUser); //

        APP.post("set-user-info", users::setUserInfo); //

        APP.get("get-user-info", users::openProfile);

        APP.post("send-message", chatStorage::sendMessage);

        APP.get("get-file", chatStorage::getFilesFromMessage);

        APP.post("delete-message", chatStorage::deleteMessage);

        APP.post("edit-message", chatStorage::editMessage);

        APP.post("message-read", chatStorage::setMessageReadIndicator);

        APP.post("create-chat", chatManager::createGroupChat);

        APP.post("create-private-chat", chatManager::createPrivateChat);

        APP.post("set-chat-settings", chatManager::changeChatSettings);

        APP.post("exit-from-chat", chatManager::exitFromChat);

        APP.post("add-user-in-chat", chatManager::addUserInChat);

        APP.get("all-users", users::getAllUsersInfo);

        APP.get("users-to-add-in-chat", users::getUsersInfoToAddInChat);

        APP.post("delete-user-from-chat", chatManager::deleteUserFromChat);

        APP.post("add-admin", chatManager::addAdminInChat);

        APP.post("delete-admin", chatManager::deleteAdminFromChat);

        APP.post("disconnect-client", sseConnectionsManager::disconnectClient);

        APP.put("alive", sseConnectionsManager::setClientOnlineStatus); //

        APP.sse("sse", sseConnectionsManager::setSseConnection);
    }
}