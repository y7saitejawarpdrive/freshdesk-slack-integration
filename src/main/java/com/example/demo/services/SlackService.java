package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
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

    // TODO: YOUR TRIAGE CHANNEL ID
    private static final String TRIAGE_CHANNEL_ID = "C0A0JC79LP9";

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://slack.com/api")
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build())
            .build();

    // --- NEW: Send Triage Card with USER SELECT Dropdown ---
    public void sendTriageMessage(String ticketId, String issueType, String priority, String desc) {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "header", "text", Map.of("type", "plain_text", "text", "🆕 Incoming Ticket #" + ticketId)),
                Map.of("type", "section", "fields", List.of(
                        Map.of("type", "mrkdwn", "text", "*Issue:* " + issueType),
                        Map.of("type", "mrkdwn", "text", "*Priority:* " + priority)
                )),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*Description:*\n" + desc)),
                // THE ASSIGNMENT DROPDOWN
                Map.of("type", "section",
                        "text", Map.of("type", "mrkdwn", "text", "👉 *Assign this ticket to:*"),
                        "accessory", Map.of(
                                "type", "users_select",
                                "placeholder", Map.of("type", "plain_text", "text", "Select Agent"),
                                "action_id", "assign_" + ticketId // Embed Ticket ID here
                        )
                )
        );

        webClient.post().uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", TRIAGE_CHANNEL_ID, "blocks", blocks, "text", "New Ticket: " + ticketId))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    // --- NEW: Update Card after Assignment ---
    public void markTriageAsAssigned(String responseUrl, String ticketId, String assignedUserId, String newChannelId) {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "✅ *Ticket #" + ticketId + " assigned to <@" + assignedUserId + ">*")),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "Workflow started in <#" + newChannelId + ">"))
        );

        WebClient.create().post().uri(responseUrl)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("replace_original", true, "blocks", blocks))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    // ... (Keep all other helper methods: getUserName, createChannel, inviteUserToChannel, etc.) ...

    public String getUserName(String userId) { /* Copy existing logic */ return "User"; }
    public String createChannel(String channelName) { /* Copy existing logic */ return null; }
    private String findChannelIdByName(String channelName) { /* Copy existing logic */ return null; }
    public void sendMessage(String channelId, String text) { /* Copy existing logic */ }
    public void inviteUserToChannel(String channelId, String userId) { /* Copy existing logic */ }
    public byte[] downloadFile(String fileUrl) { /* Copy existing logic */ return null; }
    public void sendApprovalMessage(String cid, String tid, String eta, String agent) { /* Copy existing logic */ }
    public void updateInteractionMessage(String url, String text) { /* Copy existing logic */ }
}