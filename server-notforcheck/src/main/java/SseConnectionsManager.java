import com.google.gson.Gson;
import com.google.gson.JsonObject;
import consts.SseEvents;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.eclipse.jetty.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import static consts.Constants.*;

public class SseConnectionsManager {
    private static final HashMap<String, SseClient> SSE_CLIENTS_NAMES = new HashMap<>(); // ключ - имя, значение - юзер ссе
    private static final DataBase DATABASE = Server.getDatabase();
    ChatManager chatManager = new ChatManager();

    public static HashMap<String, SseClient> getSseClientsNames() {
        return SSE_CLIENTS_NAMES;
    }

    public void login(@NotNull Context ctx) {
        String userName = ctx.queryParam(USERNAME);
        String password = ctx.queryParam(PASSWORD);
        if (DATABASE.checkDataExistsInTable(USERS, NAME, userName) && DATABASE.getValueFromString(USERS, NAME, userName, PASSWORD).equals(password)) {
            System.out.println("login");
            String sessionKey = Server.getIdString();
            DATABASE.updateValueInTable(USERS, KEY, sessionKey, NAME, userName);
            ctx.status(HttpStatus.OK_200);
            ctx.result(getStringAsJson(KEY, sessionKey).toString());
        } else {
            ctx.status(HttpStatus.UNAUTHORIZED_401);
            System.out.println("name = " + userName + " pass = " + password + " -> такого пользователя нет в базе данных");
        }
    }

    public void registerUser(@NotNull Context ctx) {
        System.out.println("registration");
        JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
        String name = requestBody.get(NAME).getAsString();
        String password = requestBody.get(PASSWORD).getAsString();
        if (Arrays.asList(WRONG_NAMES).contains(name) || name.equals("") || DATABASE.checkDataExistsInTable(USERS, NAME, name)) {
            System.out.println("ERROR: registration: Такое имя " + name + " уже есть в БД");
            ctx.status(HttpStatus.NOT_ACCEPTABLE_406);
        } else {
            String sessionKey = Server.getIdString();
            String userId = Server.getIdString();
            DATABASE.addIntoUsersTable(userId, name, password, sessionKey);
            DATABASE.createUserTable(Server.getIdString(), name, "", "");
            ctx.result(getStringAsJson(KEY, sessionKey).toString());
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * Отключение ссе клиента
     */
    public void disconnectClient(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            String username = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            if (getSseClientsNames().containsKey(username)) {
                ctx.status(HttpStatus.OK_200);
                getSseClientsNames().remove(username);
                System.out.println("Юзер " + username + " успешно отключен");
            } else {
                ctx.status(HttpStatus.UNAUTHORIZED_401);
                System.out.println("disconnect-client: Не удалось отключить пользователя: clientsInfo не содержит " + sessionKey);
            }
        }
    }

    /**
     * Устанавливает новый timeStamp клиенту
     */
    public void setClientOnlineStatus(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (checkValidSessionKey(sessionKey, ctx)) {
            String timeStamp = java.time.LocalDateTime.now().toString();
            String name = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            System.out.println(name + " is online");
            DATABASE.updateValueInTable(name, TIME_STAMP, timeStamp, FLAG, "1");
            ArrayList<String> allUsers = DATABASE.getAllUserNames();
            JsonObject userOnlineStatus = new JsonObject();
            userOnlineStatus.addProperty(NAME, name);
            userOnlineStatus.addProperty(TIME, timeStamp);
            if (allUsers != null) {
                for (String user : allUsers) {
                    if (getSseClientsNames().containsKey(user)) {
                        getSseClientsNames().get(user).sendEvent(SseEvents.USER_STATUS, userOnlineStatus.toString());
                    }
                }
            }
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * Отправка события ссе клиентам
     */
    public static void sendEvent(String sseClientsNames, String event, String eventData) {
        if (sseClientsNames != null) {
            Arrays.stream(sseClientsNames.split(",")).forEach(client -> {
                //System.out.println("SSE client = " + client);
                if (!client.equals("") && getSseClientsNames().containsKey(client)) {
                    //System.out.println("send event to " + sseClientsNames);
                    getSseClientsNames().get(client).sendEvent(event, eventData);
                }else{
                    //System.out.println("Sse client " + client + " is null");
                }
            });
        }
    }

    public void setSseConnection(SseClient client) {
        System.out.println("SSE");
        String sessionKey = client.ctx.queryParam(KEY);
        if (sessionKey != null && DATABASE.checkDataExistsInTable(USERS, KEY, sessionKey)) {
            String user = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            System.out.println("sse " + user);
            getSseClientsNames().put(user, client);
            client.onClose(() -> {
                getSseClientsNames().remove(user, client);
                System.out.println("sse: client disconnected");
            });
            String chatsFromDataBase = DATABASE.getValueFromString(user, CHAT_LIST);
            if (chatsFromDataBase != null && !chatsFromDataBase.equals("")) {
                Arrays.stream(chatsFromDataBase.split(",")).forEach(chatId -> {
                    sendEvent(user, SseEvents.UPDATE, chatManager.getChatInfoAsJson(chatId, user).toString());
                    MessageManager.sendMessageHistory(chatId, sessionKey);
                });
            }
            client.ctx.status(HttpStatus.OK_200);
        } else {
            client.ctx.status(HttpStatus.UNAUTHORIZED_401);
            System.out.println("ERROR: sse: Пользователь с ключем " + sessionKey + " не авторизован");
        }
    }

    /**
     * Перезаписывает имя ссе клиента
     */
    public void updateSseClientName(String oldName, String newName) {
        getSseClientsNames().put(newName, getSseClientsNames().get(oldName));
        getSseClientsNames().remove(oldName);
    }

    public static JsonObject getStringAsJson(String key, String value) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(key, value);
        return jsonObject;
    }

    public static boolean checkValidSessionKey(String key, @NotNull Context ctx) {
        if (key != null && DATABASE.checkDataExistsInTable(USERS, KEY, key)) {
            return true;
        } else {
            ctx.status(HttpStatus.UNAUTHORIZED_401);
            return false;
        }
    }
}