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

    // TODO: PUT YOUR TRIAGE CHANNEL ID HERE
    private static final String TRIAGE_CHANNEL_ID = "C0A0JC79LP9";

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://slack.com/api")
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build())
            .build();

    // --- 1. TRIAGE CARD (With User Select Dropdown) ---
    public void sendTriageMessage(String ticketId, String issueType, String priority, String desc) {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "header", "text", Map.of("type", "plain_text", "text", "🆕 Incoming Ticket #" + ticketId)),
                Map.of("type", "section", "fields", List.of(
                        Map.of("type", "mrkdwn", "text", "*Issue:* " + issueType),
                        Map.of("type", "mrkdwn", "text", "*Priority:* " + priority)
                )),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*Description:*\n" + desc)),
                Map.of("type", "section",
                        "text", Map.of("type", "mrkdwn", "text", "👉 *Assign this ticket to:*"),
                        "accessory", Map.of(
                                "type", "users_select",
                                "placeholder", Map.of("type", "plain_text", "text", "Select Agent"),
                                "action_id", "assign_" + ticketId
                        )
                )
        );
        webClient.post().uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", TRIAGE_CHANNEL_ID, "blocks", blocks, "text", "New Ticket " + ticketId))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    public void markTriageAsAssigned(String responseUrl, String ticketId, String assignedUserId, String newChannelId) {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "✅ *Ticket #" + ticketId + " assigned to <@" + assignedUserId + ">*")),
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "Workflow started in <#" + newChannelId + ">"))
        );
        WebClient.create().post().uri(responseUrl).header("Content-Type", "application/json")
                .bodyValue(Map.of("replace_original", true, "blocks", blocks)).retrieve().bodyToMono(String.class).subscribe();
    }

    // --- 2. APPROVAL BUTTONS ---
    public void sendApprovalMessage(String channelId, String ticketId, String etaInfo, String agentId) {
        String agentTag = (agentId != null && !agentId.isEmpty()) ? "<@" + agentId + ">" : "Support";
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "⚠️ *SLA Breach Expected*\n" + agentTag + " Approval required.\n*ETA Update:* " + etaInfo)),
                Map.of("type", "actions", "elements", List.of(
                        Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "✅ Approve"), "style", "primary", "action_id", "approve_" + ticketId),
                        Map.of("type", "button", "text", Map.of("type", "plain_text", "text", "❌ Reject"), "style", "danger", "action_id", "reject_" + ticketId)
                ))
        );
        webClient.post().uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", channelId, "blocks", blocks, "text", "Approval Needed"))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    public void updateInteractionMessage(String responseUrl, String text) {
        WebClient.create().post().uri(responseUrl).header("Content-Type", "application/json")
                .bodyValue(Map.of("replace_original", true, "text", text, "blocks", Collections.emptyList()))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    // --- 3. HELPER METHODS ---
    public String createChannel(String channelName) {
        try {
            Map response = webClient.post().uri("/conversations.create")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("name", channelName, "is_private", false))
                    .retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) return ((Map) response.get("channel")).get("id").toString();
            else if (response != null && "name_taken".equals(response.get("error"))) return findChannelIdByName(channelName);
            return null;
        } catch (Exception e) { return findChannelIdByName(channelName); }
    }

    private String findChannelIdByName(String channelName) {
        try {
            Map response = webClient.get().uri(uriBuilder -> uriBuilder.path("/conversations.list").queryParam("limit", 1000).queryParam("types", "public_channel,private_channel").build())
                    .header("Authorization", "Bearer " + slackBotToken).retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) {
                List<Map<String, Object>> channels = (List<Map<String, Object>>) response.get("channels");
                for (Map<String, Object> channel : channels) if (channel.get("name").equals(channelName)) return channel.get("id").toString();
            }
        } catch (Exception e) {}
        return null;
    }

    public void inviteUserToChannel(String channelId, String userId) {
        if (userId == null || userId.isEmpty()) return;
        try {
            webClient.post().uri("/conversations.invite")
                    .header("Authorization", "Bearer " + slackBotToken)
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("channel", channelId, "users", userId))
                    .retrieve().bodyToMono(String.class).subscribe();
        } catch (Exception e) {}
    }

    public String getUserName(String userId) {
        try {
            Map response = webClient.get().uri(uriBuilder -> uriBuilder.path("/users.info").queryParam("user", userId).build())
                    .header("Authorization", "Bearer " + slackBotToken).retrieve().bodyToMono(Map.class).block();
            if (response != null && (Boolean) response.get("ok")) return ((Map)((Map) response.get("user")).get("profile")).get("real_name").toString();
        } catch (Exception e) {}
        return "User";
    }

    public void sendMessage(String channelId, String text) {
        webClient.post().uri("/chat.postMessage").header("Authorization", "Bearer " + slackBotToken).header("Content-Type", "application/json")
                .bodyValue(Map.of("channel", channelId, "text", text)).retrieve().bodyToMono(String.class).subscribe();
    }

    public byte[] downloadFile(String fileUrl) {
        try {
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + slackBotToken);
            conn.setInstanceFollowRedirects(false);
            if (conn.getResponseCode() == 302 || conn.getResponseCode() == 307) conn = (HttpURLConnection) new URL(conn.getHeaderField("Location")).openConnection();
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) out.write(buffer, 0, bytesRead);
                return out.toByteArray();
            }
        } catch (Exception e) { return null; }
    }
}