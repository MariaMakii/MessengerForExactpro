import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import consts.SseEvents;
import io.javalin.http.Context;
import org.eclipse.jetty.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import static consts.Constants.*;

public class Users {

    private static final DataBase DATABASE = Server.getDatabase();
    private final ChatManager chatManager = new ChatManager();
    private final SseConnectionsManager sseConnectionsManager = new SseConnectionsManager();
    private final MessageManager messageManager = new MessageManager();

    /**
     * Отправляет массив всех зарегистрированных юзеров
     */
    public void getAllUsersInfo(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonArray usersArray = new JsonArray();
            ArrayList<String> allUserNames = DATABASE.getAllUserNames();
            allUserNames.remove(DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME));
            allUserNames.forEach(user -> usersArray.add(getUserInfoAsJson(user)));
            ctx.result(usersArray.toString());
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * Отправляет массив юзеров, которых можно добавить в чат
     */
    public void getUsersInfoToAddInChat(@NotNull Context ctx) {
        if (SseConnectionsManager.checkValidSessionKey(ctx.queryParam(KEY), ctx)) {
            String chatId = ctx.queryParam(CHAT_ID);
            ArrayList<String> allUserNames = DATABASE.getAllUserNames();
            String usersAlreadyInChat = DATABASE.getValueFromString(chatId, USERS);
            JsonArray usersInfoToAddInChat = new JsonArray();
            for (String user : allUserNames) {
                if (!usersAlreadyInChat.contains(user)) {
                    usersInfoToAddInChat.add(getUserInfoAsJson(user));
                }
            }
            ctx.result(usersInfoToAddInChat.toString());
            ctx.status(HttpStatus.OK_200);
        }
    }

    public void openProfile(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            String name = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            if (SseConnectionsManager.getSseClientsNames().containsKey(name)) {
                String user = Objects.equals(ctx.queryParam(NAME), "") ? name : ctx.queryParam(NAME);
                SseConnectionsManager.sendEvent(name, SseEvents.USER_INFO, getUserInfoAsJson(user).toString());
                SseConnectionsManager.sendEvent(name, SseEvents.USER_STATUS, getUserInfoAsJson(user).toString());
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
            ctx.status(HttpStatus.OK_200);
        }
    }

    public void setUserInfo(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            JsonObject userInfo = new JsonObject();
            String userName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            String newUserName = DATABASE.checkDataExistsInTable(USERS, NAME, requestBody.get(NAME).getAsString()) ? userName : requestBody.get(NAME).getAsString();
            String bio = requestBody.get(BIO).getAsString();
            String userPicture = requestBody.get(PICTURE).toString().equals("null") ? "" : requestBody.get(PICTURE).getAsString();
            if (!userName.equals(newUserName)) {
                changeUserName(userName, newUserName);
                SseConnectionsManager.sendEvent(DATABASE.getAllUserNames().toString().replace(" ", ""), SseEvents.USER_STATUS, userInfo.toString());
            }
            DATABASE.updateValueInTable(newUserName, PICTURE, userPicture, FLAG, "1");
            DATABASE.updateValueInTable(newUserName, BIO, bio, FLAG, "1");
            userInfo = getUserInfoAsJson(newUserName);
            userInfo.addProperty("oldName", userName);
            SseConnectionsManager.sendEvent(DATABASE.getAllUserNamesAsString(), SseEvents.USER_INFO, userInfo.toString());
            sendUserInfoToChats(newUserName);
            ctx.status(HttpStatus.OK_200);
        }
    }

    public void updateUserNameInChats(String oldName, String newName) {
        String chats = DATABASE.getValueFromString(oldName, CHAT_LIST);
        if (chats != null) {
            Arrays.stream(chats.split(",")).forEach(chatId -> {
                DATABASE.deleteItemFromList(chatId, USERS, oldName, FLAG, "1");
                DATABASE.addItemIntoList(chatId, USERS, newName, FLAG, "1");
                if (ChatManager.getUserRoleInChat(chatId, oldName).equals(ADMIN)) {
                    DATABASE.deleteItemFromList(chatId, ADMINS, oldName, FLAG, "1");
                    DATABASE.addItemIntoList(chatId, ADMINS, newName, FLAG, "1");
                } else if (ChatManager.getUserRoleInChat(chatId, oldName).equals(OWNER)) {
                    DATABASE.updateValueInTable(chatId, OWNER, newName, FLAG, "1");
                }
            });
        }
    }

    /**
     * Возвращает информацию о пользователе как jsonObject
     */
    public JsonObject getUserInfoAsJson(String userName) {
        JsonObject userInfo = new JsonObject();
        userInfo.addProperty(NAME, userName);
        userInfo.addProperty(BIO, DATABASE.getValueFromString(userName, BIO));
        userInfo.addProperty(TIME, DATABASE.getValueFromString(userName, TIME_STAMP));
        userInfo.addProperty(PICTURE, DATABASE.getValueFromString(userName, PICTURE));
        return userInfo;
    }

    /**
     * Получение онлайн-статуса юзера как json {name, time}
     */
    public static JsonObject getUserLastTimeStampAsJson(String user) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(NAME, user);
        jsonObject.addProperty(TIME, DATABASE.getValueFromString(user, TIME_STAMP));
        return jsonObject;
    }

    private void changeUserName(String oldUserName, String newUserName) {
        sseConnectionsManager.updateSseClientName(oldUserName, newUserName);
        updateUserNameInChats(oldUserName, newUserName);
        DATABASE.updateValueInTable(oldUserName, NAME, newUserName, FLAG, "1");
        DATABASE.renameTable(oldUserName, newUserName);
        DATABASE.updateValueInTable(USERS, NAME, newUserName, NAME, oldUserName);
        DATABASE.renameTable(oldUserName + "_" + LAST_READ_ID, newUserName + "_" + LAST_READ_ID);
        changeUserNameInMessageStorage(newUserName, oldUserName);
    }

    private void changeUserNameInMessageStorage(String newName, String oldName){
        String allUserChats = DATABASE.getValueFromString(newName, CHAT_LIST);
        if(allUserChats != null) {
            String[] allUserChatsArray = allUserChats.split(",");
            for (int i = 0; i < allUserChatsArray.length; i++) {
                String chatId = allUserChatsArray[i];
                ArrayList<String> allMessages = DATABASE.getAllIdMessages(allUserChatsArray[i]);
                allMessages.forEach(message -> {
                    JsonObject messageInChat = DATABASE.getMessageFromTable(chatId, message);
                    if (messageInChat.get(SENDER).getAsString().equals(oldName)) {
                        DATABASE.changeSenderNameInMessage(chatId, newName, messageInChat);
                        JsonObject updatedMessage = DATABASE.getMessageFromTable(chatId, message);
                        updatedMessage.addProperty(SENDER_ROLE, ChatManager.getUserRoleInChat(chatId, newName));
                        messageManager.addMineFlagToMessageAndSendForEach(chatId, updatedMessage);
                    }
                });
            }
        }
    }

    private void sendUserInfoToChats(String userName) {
        String usersChats = DATABASE.getValueFromString(userName, CHAT_LIST);
        if (usersChats != null && !usersChats.equals("")) {
            Arrays.stream(usersChats.split(",")).forEach(chat -> {
                if (!chatManager.getChatType(chat).equals(PRIVATE_CHAT)) {
                    String users = DATABASE.getValueFromString(chat, USERS);
                    Arrays.stream(users.split(",")).forEach(user -> SseConnectionsManager.sendEvent(user, SseEvents.USER_IN_CHAT, chatManager.getChatMemberInfoAsJson(chat, userName).toString()));
                } else {
                    chatManager.sendDataOfChatForUsersInChat(chat, SseEvents.UPDATE);
                }
            });
        }
    }
}