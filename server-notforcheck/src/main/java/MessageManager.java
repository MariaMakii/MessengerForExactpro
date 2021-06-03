import com.google.gson.Gson;
import com.google.gson.JsonObject;
import consts.SseEvents;
import io.javalin.http.Context;
import org.eclipse.jetty.http.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;

import static consts.Constants.*;

public class MessageManager {

    private static final DataBase DATABASE = Server.getDatabase();
    private final ChatManager CHATMANAGER = new ChatManager();

    /**
     * Отправка сообщений в чатах
     */
    public void sendMessage(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String senderName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            if (!chatId.equals("") && CHATMANAGER.getUsersInChat(chatId).contains(senderName)) {
                String messageId = Server.getIdString();
                requestBody.addProperty(ID, messageId);
                System.out.println("!!!!!!!!!!!!!!!СООБЩЕНIЕ = " + messageId);
                requestBody.addProperty(ROLE, ChatManager.getUserRoleInChat(chatId, senderName));
                requestBody.addProperty(SENDER, senderName);
                DATABASE.addMessageToStorage(chatId, requestBody);
                JsonObject message = DATABASE.getMessageFromTable(chatId, messageId);
                message.addProperty(SENDER_ROLE, ChatManager.getUserRoleInChat(chatId, senderName));
                addMineFlagToMessageAndSendForEach(chatId, message);
                ctx.status(HttpStatus.OK_200);
            } else {
                System.out.println("Этого пользователя нет в данном чате");
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    public void deleteMessage(@NotNull Context ctx) {
        JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String messageId = requestBody.get(ID).getAsString();
            String[] filesInMessage = DATABASE.getValueFromString(MESSAGE_ + chatId, ID, messageId, FILES) != null ?
                    DATABASE.getValueFromString(MESSAGE_ + chatId, ID, messageId, FILES).split(",") : null;
            if (filesInMessage != null && filesInMessage.length != 0) {
                for (String file : filesInMessage) {
                    DATABASE.deleteStringFromTable(FILES_ + chatId, ID, file);
                }
            }
            DATABASE.deleteStringFromTable(MESSAGE_ + chatId, ID, messageId);
            DATABASE.deleteStringFromTable(FILES_ + chatId, MESSAGE, messageId);
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty(ID, messageId);
            SseConnectionsManager.sendEvent(CHATMANAGER.getUsersInChat(chatId), SseEvents.MESSAGE_IS_DELETED, jsonObject.toString());
            updateLastReadMessageAfterDeleteMessage(chatId, messageId);
            ctx.status(HttpStatus.OK_200);
        }
    }

    private void updateLastReadMessageAfterDeleteMessage(String chatId, String deletedMessageId){
        String usersInChat = CHATMANAGER.getUsersInChat(chatId);
        String prevMessageId = getPrevMessageId(chatId, deletedMessageId);
        Arrays.stream(usersInChat.split(",")).forEach(userInChat->{
            if(DATABASE.getLastReadId(chatId, userInChat).equals(deletedMessageId)){
                SseConnectionsManager.sendEvent(userInChat, SseEvents.UPDATE, prevMessageId);
            }

        });
    }

    private String getPrevMessageId(String chatId, String messageId){
        String prevMessageId = "";
        ArrayList<String> allMessages = DATABASE.getAllIdMessages(chatId);
        for (String message : allMessages) {
            if (!message.equals(messageId)) {
                prevMessageId = message;
            } else {
                break;
            }
        }
        return prevMessageId;
    }

    public void getFilesFromMessage(@NotNull Context ctx) {
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            String fileId = ctx.queryParam(ID);
            String chatId = ctx.queryParam(CHAT_ID);
            String fileType = DATABASE.getValueFromString(FILES_ + chatId, ID, fileId, TYPE);
            System.out.println(fileType);
            ctx.contentType(fileType);
            ctx.header("Content-Disposition", "attachment; filename=zzz.jpg");
            ctx.header("Cache-Control", "public, max-age=60, immutable");
            ctx.result(DATABASE.getValueFromString(FILES_ + chatId, ID, fileId, BYTES));
            ctx.status(HttpStatus.OK_200);
        }
    }

    /**
     * POST запрос на редактирование сообщения
     */
    public void editMessage(@NotNull Context ctx) {
        System.out.println("editMessage");
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String user = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            if (CHATMANAGER.getUsersInChat(chatId).contains(user)){
                if (DATABASE.checkDataExistsInTable(MESSAGE_ + chatId, ID, requestBody.get(ID).getAsString())) {
                    DATABASE.editMessageInStorage(chatId, requestBody);
                    JsonObject message = DATABASE.getMessageFromTable(chatId, requestBody.get(ID).getAsString());
                    addMineFlagToMessageAndSendForEach(chatId, message);
                    ctx.status(HttpStatus.OK_200);
                }else {
                    System.out.println("Сообщение с таким id Не найдено");
                    ctx.status(HttpStatus.NOT_FOUND_404);
                }
            }else{
                System.out.println("Такого юзера нет в чате");
                ctx.status(HttpStatus.FORBIDDEN_403);
            }
        }
    }

    /**
     * Добавляет flag mine, определяющий принадлежит ли сообщение юзеру
     * Оправляет сообщение всем в чате
     */
    public void addMineFlagToMessageAndSendForEach(String chatId, JsonObject message) {
        System.out.println("addMineFlagToMessage");
        Arrays.stream(CHATMANAGER.getUsersInChat(chatId).split(",")).forEach(user -> {
            System.out.println(user);
            message.addProperty(MINE, message.get(SENDER).getAsString().equals(user));
            //System.out.println("addMineFlagToMessage MESSAGE = " + message);
            SseConnectionsManager.sendEvent(user, SseEvents.MESSAGE, message.toString());
        });
    }

    /**
     * Добавление индекатора прочитано к сообщению
     */
    public void setMessageReadIndicator(@NotNull Context ctx) {
        System.out.println("IN setMessageReadIndicator");
        String sessionKey = ctx.queryParam(KEY);
        if (SseConnectionsManager.checkValidSessionKey(sessionKey, ctx)) {
            JsonObject requestBody = new Gson().fromJson(ctx.body(), JsonObject.class);
            String chatId = requestBody.get(CHAT_ID).getAsString();
            String messageId = requestBody.get(ID).getAsString();
            System.out.println(requestBody.toString());
            String userName = DATABASE.getValueFromString(USERS, KEY, sessionKey, NAME);
            JsonObject message = DATABASE.getMessageFromTable(chatId, requestBody.get(ID).getAsString());
            if(message.get(READ).toString().equals("true")){
                DATABASE.setLastReadId(chatId, messageId, userName);
                JsonObject jsonObject = CHATMANAGER.getChatInfoAsJson(chatId, userName);
                jsonObject.addProperty(LAST_READ_ID, DATABASE.getLastReadId(chatId, userName));
                System.out.println(userName + " " +  message.get(SENDER).getAsString());
                message.addProperty(MINE, message.get(SENDER).getAsString().equals(userName));
                System.out.println("LAST = " + DATABASE.getLastReadId(chatId, userName));
                SseConnectionsManager.sendEvent(userName, SseEvents.MESSAGE, message.toString());
                SseConnectionsManager.sendEvent(userName, SseEvents.UPDATE,
                        jsonObject.toString());
                ctx.status(HttpStatus.OK_200);
            }else{
                System.out.println("message from store = " + message.toString());
                message.addProperty(READ, true);
                DATABASE.setTrueReadIndicatorIntoMessage(chatId, message);
                JsonObject message1 = DATABASE.getMessageFromTable(chatId, requestBody.get(ID).getAsString());
                System.out.println("ПрочIтанное сообщение = " + message1);
                String senderName = message1.get(SENDER).getAsString();
                message1.addProperty(SENDER_ROLE, ChatManager.getUserRoleInChat(chatId, senderName));
                System.out.println(userName + " " +  senderName);
                message1.addProperty(MINE, message.get(SENDER).getAsString().equals(userName));
                SseConnectionsManager.sendEvent(senderName, SseEvents.MESSAGE, message1.toString());
                DATABASE.setLastReadId(chatId, messageId, userName);
                JsonObject jsonObject = CHATMANAGER.getChatInfoAsJson(chatId, userName);
                jsonObject.addProperty(LAST_READ_ID, DATABASE.getLastReadId(chatId, userName));
                System.out.println("LAST = " + DATABASE.getLastReadId(chatId, userName));

                SseConnectionsManager.sendEvent(userName, SseEvents.MESSAGE, message1.toString());
                SseConnectionsManager.sendEvent(userName, SseEvents.UPDATE,
                        jsonObject.toString());

                message1.addProperty(MINE, message.get(SENDER).getAsString().equals(senderName));
                SseConnectionsManager.sendEvent(senderName, SseEvents.MESSAGE, message1.toString());


                ctx.status(HttpStatus.OK_200);
            }
        }
    }

    public static void sendMessageHistory(String chatId, String key) {
        ArrayList<String> allIdMessages = DATABASE.getAllIdMessages(chatId);
        String clientName = DATABASE.getValueFromString(USERS, KEY, key, NAME);
        allIdMessages.forEach(messageId -> {
            JsonObject message = DATABASE.getMessageFromTable(chatId, messageId);
            String senderName = message.get(SENDER).getAsString();
            message.addProperty(SENDER_ROLE, ChatManager.getUserRoleInChat(chatId, senderName));
            message.addProperty(MINE, senderName.equals(clientName));
            SseConnectionsManager.sendEvent(clientName, SseEvents.MESSAGE, message.toString());
        });
    }
}