import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.javalin.http.Context;
import org.eclipse.jetty.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;

import static consts.Constants.*;

import consts.SseEvents;

public class ChatManager {

    private static final DataBase DATABASE = Server.getDatabase();

    /**
     * Создание группового чата...
     */
    public void createGroupChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            // проверка содержания необходимых параметров в запросе на создание чата
            if (requestBody.has(CHAT_NAME) && requestBody.has(USERS) && requestBody.has(TYPE)) {
                String chatName = requestBody.get(CHAT_NAME).getAsString();
                String chatType = requestBody.get(TYPE).getAsString();
                System.out.println("CHAT TYPE FROM CLIENT = " + chatType);
                String chatId = Server.getIdString();
                String ownerName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);

                DATABASE.createChat(chatId, chatName, chatType, ownerName);
                if (requestBody.has(BIO)) {
                    DATABASE.updateValueInTable(chatId, BIO, requestBody.get(BIO).getAsString(), FLAG, "1");
                }
                if (requestBody.has(PICTURE)) {
                    System.out.println("ADD PICTURE");
                    addChatPicture(chatId, requestBody.get(PICTURE).getAsString());
                }
                JsonArray usersInChat = requestBody.get(USERS).getAsJsonArray();
                usersInChat.add(ownerName);
                addUsersInChat(chatId, usersInChat);
                sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                sendChatId(chatId, ctx);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.result("При создании чата обязательно указание его имени, типа и юзеров");
                ctx.status(HttpStatus.CONFLICT_409);
            }
        }
    }

    /**
     * Отправка ID чата как jsonObject
     */
    private void sendChatId(String chatId, Context ctx) {
        JsonObject chatIdJson = new JsonObject();
        chatIdJson.addProperty(CHAT_ID, chatId);
        ctx.result(chatIdJson.toString());
    }

    /**
     * Добавление всех юзеров в чат при создании чата
     */
    private void addUsersInChat(String chatId, JsonArray users) {
        int numberOfMembers = users.size();
        for (int i = 0; i < numberOfMembers; i++) {
            DATABASE.addUserInChat(chatId, users.get(i).getAsString());
            DATABASE.setLastReadId(chatId, "", users.get(i).getAsString());
        }
    }

    /**
     * Создание личного чата
     */
    public void createPrivateChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String creatorName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            String userName = requestBody.get(USER).getAsString();
            if (!isPrivateChatAlreadyExists(creatorName, userName, ctx)) {
                String chatId = Server.getIdString();
                DATABASE.createChat(chatId, creatorName + "_" + userName, PRIVATE_CHAT, creatorName);
                DATABASE.addUserInChat(chatId, creatorName);
                DATABASE.addUserInChat(chatId, userName);
                sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                sendChatId(chatId, ctx);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.CONFLICT_409);
                System.out.println("create-private-chat: Такой чат уже есть");
            }
        }
    }

    /**
     * Определяет тип чата из которого выходит юзер и перенаправляет на нужный метод
     */
    public void exitFromChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            System.out.println("exitFromChat");
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String type = getChatType(chatId);
            String usersInChat = getUsersInChat(chatId);
            String userForDelete = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);

            if (type.equals(PRIVATE_CHAT)) {
                exitFromPrivateChat(chatId, usersInChat);
            } else if (type.equals(SMART_CHAT)) {
                exitFromSmartChat(chatId, userForDelete);
            } else {
                exitFromSimpleChat(chatId, usersInChat, userForDelete);
            }
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * При открытии чата отсылается информация о юзерах в чате
     */
    public void openChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            System.out.println("+++++++++++++++++++++++++++++++++++ OPEN CHAT +++++++++++++++++++++++++++++++");
            String chatId = ctx.queryParam(CHAT_ID);
            String usersInChat = getUsersInChat(chatId);
            String openerName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            Arrays.stream(usersInChat.split(",")).forEach(user -> {
                if (!user.equals(openerName)) {
                    String userInfo = getChatMemberInfoAsJson(chatId, user).toString();
                    SseConnectionsManager.sendEvent(openerName, SseEvents.USER_IN_CHAT, userInfo);
                    SseConnectionsManager.sendEvent(openerName, SseEvents.USER_STATUS, Users.getUserLastTimeStampAsJson(user).toString());
                }
            });
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * Iзменение картинки, био и названия чата
     */
    public void changeChatSettings(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String userName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            if (isUserRightsEnough(chatId, userName)) {
                String bio = requestBody.get(BIO).getAsString();
                String chatName = requestBody.get(CHAT_NAME).getAsString();
                String picture = requestBody.get(PICTURE).toString().equals("") ? null : requestBody.get(PICTURE).getAsString();
                String type = requestBody.get(TYPE).getAsString();

                if (!type.equals(getChatType(chatId))) {
                    DATABASE.changeChatType(chatId, type);
                    sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                    String[] usersInChat = getUsersInChat(chatId).split(",");
                    for (String user : usersInChat) {
                        for (String userInCha : usersInChat) {
                            if (!user.equals(userInCha)) {
                                SseConnectionsManager.sendEvent(user, SseEvents.USER_IN_CHAT, getChatMemberInfoAsJson(chatId, userInCha).toString());
                            }
                        }
                    }
                }
                DATABASE.updateValueInTable(chatId, NAME, chatName, FLAG, "1");
                DATABASE.updateValueInTable(chatId, PICTURE, picture, FLAG, "1");
                DATABASE.updateValueInTable(chatId, BIO, bio, FLAG, "1");
                sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    /**
     * Удаление юзера из чата
     */
    public void deleteUserFromChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(ctx.queryParam(KEY), ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String user = requestBody.get(USER).getAsString();
            if (isUserRightsEnough(chatId, DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME))) {
                DATABASE.deleteUserFromChat(chatId, user);
                String usersInChat = getUsersInChat(chatId);
                SseConnectionsManager.sendEvent(user, SseEvents.CHAT_IS_DELETED, getChatIdAsJson(chatId).toString());
                SseConnectionsManager.sendEvent(usersInChat, SseEvents.USER_IS_DELETED, getChatMemberInfoAsJson(chatId, user).toString());
                DATABASE.deleteStringFromTable(user + "_" + LAST_READ_ID, CHAT_ID, chatId);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    public void addChatPicture(String chatId, String pictureBytes) {
        DATABASE.updateValueInTable(chatId, PICTURE, pictureBytes, FLAG, "1");
    }

    /**
     * Передаваемый юзер становится админом группы
     */
    public void addAdminInChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            System.out.println("ADD ADMIN");
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            if (isUserRightsEnough(chatId, DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME))) {
                String user = requestBody.get(USER).getAsString();
                DATABASE.addItemIntoList(chatId, ADMINS, user, FLAG, "1");
                sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                sendUserInfoInChat(chatId, user);
                updateSenderRoleInMessages(chatId, user, ADMIN);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    private void updateSenderRoleInMessages(String chatId, String userName, String newRole){
        System.out.println("updateSenderRoleInMessages");
        ArrayList<String> allMassagesInChat = DATABASE.getAllIdMessages(chatId);
        for (String messageFromChat : allMassagesInChat) {
            JsonObject message = DATABASE.getMessageFromTable(chatId, messageFromChat);
            DATABASE.updateValueInTable(MESSAGE_ + chatId, ROLE, newRole, SENDER, userName);
            JsonObject updatedRoleMessage = DATABASE.getMessageFromTable(chatId, message.get(ID).getAsString());
            if (updatedRoleMessage.get(SENDER).toString().equals(userName)) {
                Arrays.stream(getUsersInChat(chatId).split(",")).forEach(userInChat -> SseConnectionsManager.sendEvent(userInChat, SseEvents.MESSAGE, updatedRoleMessage.toString()));
            }
        }
    }

    /**
     * Передаваемый юзер удаляется из админов группы
     */
    public void deleteAdminFromChat(@NotNull Context ctx) {
        String key = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(key, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            if (isUserRightsEnough(chatId, DATABASE.getValueFromString(USERS, KEY, key, NAME))) {
                System.out.println("DELETE ADMIN");
                String user = requestBody.get(USER).getAsString();
                DATABASE.deleteItemFromList(chatId, ADMINS, user, FLAG, "1");
                sendDataOfChatForUsersInChat(chatId, SseEvents.UPDATE);
                sendUserInfoInChat(chatId, user);
                updateSenderRoleInMessages(chatId, user, NONE);
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    /**
     * Добавление юзера в групповой чат
     */
    public void addUserInChat(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            System.out.println("ADD USER IN CHAT");
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String user = requestBody.get(USER).getAsString();
            String chatId = requestBody.get(CHAT_ID).getAsString();
            if (isUserRightsEnough(chatId, DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME))) {
                String usersInChat = getUsersInChat(chatId);
                if (!usersInChat.contains(user)) {
                    DATABASE.addUserInChat(chatId, user);
                    SseConnectionsManager.sendEvent(usersInChat, SseEvents.USER_IN_CHAT, getChatMemberInfoAsJson(chatId, user).toString());
                    SseConnectionsManager.sendEvent(user, SseEvents.UPDATE, getChatInfoAsJson(chatId, user).toString());
                    MessageManager.sendMessageHistory(chatId, sessionKey);
                }
                ctx.status(HttpStatus.OK_200);
            } else {
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    public String getChatType(String chatId) {
        return DATABASE.getValueFromString(chatId, TYPE);
    }

    private void sendUserInfoInChat(String chatId, String user) {
        System.out.println("HERE");
        String usersInChat = getUsersInChat(chatId);
        System.out.println(usersInChat);
        Arrays.stream(usersInChat.split(",")).forEach(userInChat -> {
            System.out.println("userInChat = " + userInChat);
            if (!userInChat.equals(user) && SseConnectionsManager.getSseClientsNames().containsKey(userInChat)) {
                System.out.println("sendUserInfoInChat user = " + user + " to " + userInChat);
                SseConnectionsManager.sendEvent(userInChat, SseEvents.USER_IN_CHAT, getChatMemberInfoAsJson(chatId, user).toString());
            }
        });
        System.out.println("END OF sendUserInfoInChat");
    }

    /**
     * Получение картинки чата как строки байтов
     */
    public String getChatPicture(String chatId, String sessionKey) {
        String chatType = getChatType(chatId);
        if(chatType!=null){
            if (chatType.equals(PRIVATE_CHAT)) {
                String openerName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
                String companionName = getUsersInChat(chatId).replace(openerName, "").replace(",", "");
                return DATABASE.getValueFromString(companionName, PICTURE);
            } else {
                return DATABASE.getValueFromString(chatId, PICTURE);
            }
        }else{
            return null;
        }
    }

    /**
     * Проверка существования личного чата
     */
    private Boolean isPrivateChatAlreadyExists(String creator, String user, Context ctx) {
        String allCreatorsChats = DATABASE.getValueFromString(creator, CHAT_LIST);
        if (allCreatorsChats != null && !allCreatorsChats.equals("")) {
            String[] allCreatorsChatsArray = allCreatorsChats.split(",");
            for (String chatId : allCreatorsChatsArray) {
                if (getChatType(chatId).equals(PRIVATE_CHAT)) {
                    String usersInChat = getUsersInChat(chatId);
                    // если найден приватный чат с такими юзерами
                    if (usersInChat.equals(creator + "," + user) || usersInChat.equals(user + "," + creator)) {
                        sendChatId(chatId, ctx);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Может ли юзер редактировать чат
     */
    private boolean isUserRightsEnough(String chatId, String userName) {
        String chatType = getChatType(chatId);
        return chatType.equals(SMART_CHAT) && (getUserRoleInChat(chatId, userName).equals(ADMIN) || getUserRoleInChat(chatId, userName).equals(OWNER)) || chatType.equals(SIMPLE_CHAT);
    }

    /**
     * При выходе из приватного чата - чат удаляется из БД
     * Связи между чатом и юзерами удаляются
     * Юзеры оповещаются об удалении чата
     */
    private void exitFromPrivateChat(String chatId, String usersInChat) {
        System.out.println("exitFromPrivateChat");
        DATABASE.deleteUserFromChat(chatId, usersInChat);
        DATABASE.deleteChat(chatId);
        SseConnectionsManager.sendEvent(usersInChat, SseEvents.CHAT_IS_DELETED, getChatIdAsJson(chatId).toString());
        Arrays.stream(usersInChat.split(",")).forEach(user-> DATABASE.deleteStringFromTable(user + "_" + LAST_READ_ID, CHAT_ID, chatId));
    }

    private JsonObject getChatIdAsJson(String chatId) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(ID, chatId);
        return jsonObject;
    }

    /**
     * Возвращает строку с именами юзеров в чате через запятую
     */
    public String getUsersInChat(String chatId) {
        return DATABASE.getValueFromString(chatId, USERS);
    }

    private void exitFromSimpleChat(String chatId, String usersInChat, String userForDelete) {
        System.out.println("exitFromSimpleChat");
        if (usersInChat.split(",").length == 1) {
            DATABASE.deleteUserFromChat(chatId, userForDelete);
            DATABASE.deleteChat(chatId);
        } else {
            String users = getUsersInChat(chatId);
            if (users != null) {
                SseConnectionsManager.sendEvent(users, SseEvents.USER_IS_DELETED, getChatMemberInfoAsJson(chatId, userForDelete).toString());
            }
            DATABASE.deleteUserFromChat(chatId, userForDelete);
        }
        DATABASE.deleteStringFromTable(userForDelete + "_" + LAST_READ_ID, CHAT_ID, chatId);
        SseConnectionsManager.sendEvent(userForDelete, SseEvents.CHAT_IS_DELETED, getChatIdAsJson(chatId).toString());
    }

    /**
     * При выходе из умного чата проверяется выходит ли овнер
     */
    private void exitFromSmartChat(String chatId, String userForDelete) {
        System.out.println("exitFromSmartChat");
        String usersInChat = DATABASE.getValueFromString(chatId, USERS);
        if (getUserRoleInChat(chatId, userForDelete).equals(OWNER)) {
            Arrays.stream(usersInChat.split(",")).forEach(user -> {
                DATABASE.deleteUserFromChat(chatId, user);
                SseConnectionsManager.sendEvent(user, SseEvents.CHAT_IS_DELETED, getChatInfoAsJson(chatId, user).toString());
                DATABASE.deleteStringFromTable(user + "_" + LAST_READ_ID, CHAT_ID, chatId);
            });
            DATABASE.deleteChat(chatId);
        } else {
            DATABASE.deleteUserFromChat(chatId, userForDelete);
            SseConnectionsManager.sendEvent(userForDelete, SseEvents.CHAT_IS_DELETED, getChatInfoAsJson(chatId, userForDelete).toString());
            SseConnectionsManager.sendEvent(usersInChat, SseEvents.USER_IS_DELETED, getChatMemberInfoAsJson(chatId, userForDelete).toString());
            DATABASE.deleteStringFromTable(userForDelete + "_" + LAST_READ_ID, CHAT_ID, chatId);
        }
    }

    /**
     * Отправка данных о чате всем юзерам в этом чате
     */
    public void sendDataOfChatForUsersInChat(String chatId, String event) {
        String usersInChat = DATABASE.getValueFromString(chatId, USERS);
        System.out.println("InSendDataOfChatForUsersInChat\n" + usersInChat);
        if (!usersInChat.equals("")) {
            System.out.println("sendDataOfChatForUsersInChat");
            Arrays.stream(usersInChat.split(",")).forEach(user ->
                    {
                        System.out.println("user = " + user);
                        SseConnectionsManager.sendEvent(user, event, getChatInfoAsJson(chatId, user).toString());
                    });
            System.out.println("END OF sendDataOfChatForUsersInChat");
        }
    }

    /**
     * Возвращает роль юзера в чате
     */
    public static String getUserRoleInChat(String chatId, String userName) {
        String admins = DATABASE.getValueFromString(chatId, ADMINS);
        if (admins != null) {
            if (admins.contains(userName)) {
                return ADMIN;
            }
        }
        if (userName.equals(DATABASE.getValueFromString(chatId, OWNER))) {
            return OWNER;
        }
        return NONE;
    }

    /**
     * Возвращает
     */
    public JsonObject getChatInfoAsJson(String chatId, String openerName) {
        System.out.println("getChatInfoAsJson");
        JsonObject dataOfChat = new JsonObject();
        String type = getChatType(chatId);
        dataOfChat.addProperty(ID, chatId);
        dataOfChat.addProperty(TYPE, type);
        dataOfChat.addProperty(LAST_READ_ID, DATABASE.getLastReadId(chatId, openerName));
        dataOfChat.addProperty(PICTURE, getChatPicture(chatId, DATABASE.getValueFromString(USERS, NAME, openerName, KEY)));
        if (type.equals(PRIVATE_CHAT)) {
            String companionName = getUsersInChat(chatId).replace(openerName, "").replace(",", "");
            dataOfChat.addProperty(TITLE, companionName);
        } else {
            dataOfChat.addProperty(TITLE, DATABASE.getValueFromString(chatId, NAME));
            dataOfChat.addProperty(BIO, DATABASE.getValueFromString(chatId, BIO));
            dataOfChat.addProperty(ROLE, getUserRoleInChat(chatId, openerName));
        }
        return dataOfChat;
    }

    /**
     * Возвращает имя, timestamp, картинку и роль юзера в чате
     */
    public JsonObject getChatMemberInfoAsJson(String chatId, String user) {
        if (chatId != null) {
            JsonObject dataOfUser = new JsonObject();
            dataOfUser.addProperty(NAME, user);
            dataOfUser.addProperty(BIO, DATABASE.getValueFromString(user, BIO));
            dataOfUser.addProperty(TIME, DATABASE.getValueFromString(user, TIME_STAMP));
            dataOfUser.addProperty(PICTURE, DATABASE.getValueFromString(user, PICTURE));
            if (!chatId.equals("")) {
                dataOfUser.addProperty(ID, chatId);
                if (!getChatType(chatId).equals(PRIVATE_CHAT)) {
                    dataOfUser.addProperty(ROLE, getUserRoleInChat(chatId, user));
                }
            }
            return dataOfUser;
        } else {
            return null;
        }
    }
}