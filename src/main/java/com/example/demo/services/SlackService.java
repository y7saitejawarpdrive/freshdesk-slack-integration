package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SlackService {

    @Value("${slack.bot.token}")
    private String slackBotToken;

    private final WebClient webClient = WebClient.create("https://slack.com/api");

    public String createChannel(String channelName) {

        Map response = webClient.post()
                .uri("/conversations.create")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "name", channelName,
                        "is_private", false
                ))
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null || !(Boolean) response.get("ok")) {
            throw new RuntimeException("Slack channel creation failed: " + response);
        }

        Map channel = (Map) response.get("channel");
        return channel.get("id").toString();
    }

    public void sendMessage(String channelId, String text) {
        webClient.post()
                .uri("/chat.postMessage")
                .header("Authorization", "Bearer " + slackBotToken)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "channel", channelId,
                        "text", text
                ))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe();
    }
}
