package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SlackService {

    @Value("${slack.bot.token}")
    private String slackBotToken;

    private final WebClient webClient = WebClient.create("https://slack.com/api");

    // --- Channel Logic ---
    public String createChannel(String channelName) {
        try {
            Map response = webClient.post()
                    .uri("/conversations.create")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("name", channelName, "is_private", false))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && (Boolean) response.get("ok")) {
                Map channel = (Map) response.get("channel");
                return channel.get("id").toString();
            } else if (response != null && "name_taken".equals(response.get("error"))) {
                System.out.println("⚠ Channel name taken. Searching for existing ID...");
                return findChannelIdByName(channelName);
            }
            throw new RuntimeException("Slack channel creation failed");
        } catch (Exception e) {
            throw new RuntimeException("Error in createChannel: " + e.getMessage());
        }
    }

    private String findChannelIdByName(String channelName) {
        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/conversations.list")
                            .queryParam("limit", 1000)
                            .queryParam("types", "public_channel,private_channel")
                            .build())
                    .header("Authorization", "Bearer " + slackBotToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && (Boolean) response.get("ok")) {
                List<Map<String, Object>> channels = (List<Map<String, Object>>) response.get("channels");
                for (Map<String, Object> channel : channels) {
                    if (channel.get("name").equals(channelName)) {
                        return channel.get("id").toString();
                    }
                }
            }
        } catch (Exception e) {}
        throw new RuntimeException("Channel exists but ID not found.");
    }

    public void sendMessage(String channelId, String text) {
        webClient.post()
                .uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", channelId, "text", text))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe();
    }

    // --- User Group Logic ---
    public List<String> getUserGroupMembers(String userGroupId) {
        try {
            Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/usergroups.users.list")
                            .queryParam("usergroup", userGroupId)
                            .queryParam("include_disabled", false)
                            .build())
                    .header("Authorization", "Bearer " + slackBotToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response != null && (Boolean) response.get("ok")) {
                return (List<String>) response.get("users");
            }
        } catch (Exception e) {}
        return Collections.emptyList();
    }

    public void inviteUsersToChannel(String channelId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        String usersCommaSeparated = String.join(",", userIds);
        try {
            webClient.post()
                    .uri("/conversations.invite")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("channel", channelId, "users", usersCommaSeparated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe();
        } catch (Exception e) {}
    }

    // --- NEW: Download File ---
    public byte[] downloadFile(String fileUrl) {
        try {
            // NOTE: We do NOT use the base URL here, we use the specific fileUrl
            return WebClient.create().get()
                    .uri(fileUrl)
                    .header("Authorization", "Bearer " + slackBotToken)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            System.out.println("⚠ Error downloading file from Slack: " + e.getMessage());
            return null;
        }
    }
}