package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SlackService {

    @Value("${slack.bot.token}")
    private String slackBotToken;

    private final WebClient webClient = WebClient.create("https://slack.com/api");

    // --- Channel & User Logic (Standard) ---
    public String createChannel(String channelName) {
        try {
            Map response = webClient.post().uri("/conversations.create")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("name", channelName, "is_private", false))
                    .retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) {
                return ((Map) response.get("channel")).get("id").toString();
            } else if (response != null && "name_taken".equals(response.get("error"))) {
                return findChannelIdByName(channelName);
            }
            return null;
        } catch (Exception e) { return findChannelIdByName(channelName); }
    }

    private String findChannelIdByName(String channelName) {
        try {
            Map response = webClient.get().uri(uriBuilder -> uriBuilder.path("/conversations.list").queryParam("limit", 1000).queryParam("types", "public_channel,private_channel").build())
                    .header("Authorization", "Bearer " + slackBotToken).retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) {
                List<Map<String, Object>> channels = (List<Map<String, Object>>) response.get("channels");
                for (Map<String, Object> channel : channels) {
                    if (channel.get("name").equals(channelName)) return channel.get("id").toString();
                }
            }
        } catch (Exception e) {}
        return null;
    }

    public void sendMessage(String channelId, String text) {
        webClient.post().uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", channelId, "text", text))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    public List<String> getUserGroupMembers(String userGroupId) {
        try {
            Map response = webClient.get().uri(uriBuilder -> uriBuilder.path("/usergroups.users.list").queryParam("usergroup", userGroupId).queryParam("include_disabled", false).build())
                    .header("Authorization", "Bearer " + slackBotToken).retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) return (List<String>) response.get("users");
        } catch (Exception e) {}
        return Collections.emptyList();
    }

    public void inviteUsersToChannel(String channelId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        try {
            webClient.post().uri("/conversations.invite")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("channel", channelId, "users", String.join(",", userIds)))
                    .retrieve().bodyToMono(String.class).subscribe();
        } catch (Exception e) {}
    }

    // --- FIX: REDIRECT-SAFE DOWNLOADER ---
    public byte[] downloadFile(String fileUrl) {
        try {
            System.out.println("⬇ Downloading file: " + fileUrl);
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + slackBotToken);
            conn.setInstanceFollowRedirects(false); // We handle redirects manually

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM || status == 307) {
                String newUrl = conn.getHeaderField("Location");
                System.out.println("⬇ Redirecting to AWS S3: " + newUrl);
                // Connect to S3 WITHOUT the Bearer Token (AWS rejects extra headers)
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
            }

            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            System.out.println("❌ Download Error: " + e.getMessage());
            return null;
        }
    }
}