package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SlackService {

    @Value("${slack.bot.token}")
    private String slackBotToken;

    private final WebClient webClient = WebClient.create("https://slack.com/api");

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

            if (response == null || !(Boolean) response.get("ok")) {
                throw new RuntimeException("Slack channel creation failed: " + response);
            }
            Map channel = (Map) response.get("channel");
            return channel.get("id").toString();
        } catch (Exception e) {
            System.out.println("⚠ Error creating channel (might exist): " + e.getMessage());
            // If creation fails, we can't easily return an ID without searching.
            // For now, let the controller handle the error.
            throw e;
        }
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

    // --- NEW: Get User IDs from a User Group (e.g., S0A1L56DJ3B) ---
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
        } catch (Exception e) {
            System.out.println("⚠ Error fetching user group " + userGroupId + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    // --- NEW: Invite Users to Channel ---
    public void inviteUsersToChannel(String channelId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return;

        // Slack API expects comma-separated string of users
        String usersCommaSeparated = String.join(",", userIds);

        try {
            webClient.post()
                    .uri("/conversations.invite")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("channel", channelId, "users", usersCommaSeparated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(response -> System.out.println("✅ Invited users to " + channelId));
        } catch (Exception e) {
            System.out.println("⚠ Error inviting users (some might already be in channel): " + e.getMessage());
        }
    }
}