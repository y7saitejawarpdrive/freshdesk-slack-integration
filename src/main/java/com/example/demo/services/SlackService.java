package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    // --- Channel & User Logic (Unchanged) ---
    public String createChannel(String channelName) {
        try {
            Map response = webClient.post().uri("/conversations.create")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("name", channelName, "is_private", false))
                    .retrieve().bodyToMono(Map.class).block();

            if (response != null && (Boolean) response.get("ok")) {
                Map channel = (Map) response.get("channel");
                return channel.get("id").toString();
            } else if (response != null && "name_taken".equals(response.get("error"))) {
                return findChannelIdByName(channelName);
            }
            return null;
        } catch (Exception e) { return findChannelIdByName(channelName); }
    }

    private String findChannelIdByName(String channelName) {
        try {
            Map response = webClient.get().uri(uriBuilder -> uriBuilder.path("/conversations.list").queryParam("limit", 1000).queryParam("types", "public_channel,private_channel").build())
                    .header("Authorization", "Bearer " + slackBotToken)
                    .retrieve().bodyToMono(Map.class).block();

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

    // --- FIX: ROBUST FILE DOWNLOADER ---
    public byte[] downloadFile(String fileUrl) {
        try {
            System.out.println("⬇ Downloading file from: " + fileUrl);
            URL url = new URL(fileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // CRITICAL: Slack requires the Bearer token to authorize the download
            connection.setRequestProperty("Authorization", "Bearer " + slackBotToken);
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();

            // Handle Redirects manually if needed (Slack sometimes redirects to AWS S3)
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP || responseCode == HttpURLConnection.HTTP_MOVED_PERM) {
                String newUrl = connection.getHeaderField("Location");
                System.out.println("⬇ Following redirect to: " + newUrl);
                // Redirects to AWS S3 usually do NOT want the Bearer token anymore
                connection = (HttpURLConnection) new URL(newUrl).openConnection();
            }

            try (InputStream in = connection.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            System.out.println("❌ Error downloading file: " + e.getMessage());
            return null;
        }
    }
}