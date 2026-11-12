package com.ltm.lab01;

import java.io.Serializable;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Gói tin chuẩn dùng chung giữa ChatServer và ChatClient qua Java Serialization (TCP).
 * Có thể mang văn bản (Text/Emoji), trạng thái Online/Nhóm, File đính kèm (byte[])
 * hoặc luồng âm thanh PCM / khung hình JPEG cho cuộc gọi Audio/Video.
 */
public class ChatPacket implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public enum Type {
        LOGIN,
        LOGIN_ACK,
        STATE_UPDATE,
        PUBLIC_MSG,
        PRIVATE_MSG,
        GROUP_CREATE,
        GROUP_JOIN,
        GROUP_LEAVE,
        GROUP_MSG,
        FILE_TRANSFER,
        CALL_REQUEST,
        CALL_ACCEPT,
        CALL_REJECT,
        CALL_END,
        AUDIO_CHUNK,
        VIDEO_FRAME,
        SYSTEM,
        ERROR
    }

    public Type type;
    public String sender;
    public String target;
    public String groupName;
    public String scope;       // "public" | "private" | "group" (dùng cho gửi file)
    public String text;
    public String fileName;
    public byte[] binaryData;  // Dữ liệu file, PCM audio hoặc JPEG video
    public List<String> onlineUsers;
    public Map<String, List<String>> groups;
    public boolean success;
    public String callMode;    // "audio" | "video"
    public String timestamp;

    public ChatPacket(Type type) {
        this.type = type;
        this.timestamp = LocalTime.now().format(TIME_FMT);
    }

    public static ChatPacket simple(Type type, String sender, String text) {
        ChatPacket p = new ChatPacket(type);
        p.sender = sender;
        p.text = text;
        return p;
    }
}
