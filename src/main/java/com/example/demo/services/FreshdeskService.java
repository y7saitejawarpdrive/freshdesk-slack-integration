package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class FreshdeskService {

    @Value("${freshdesk.api.key}")
    private String apiKey;

    @Value("${freshdesk.domain}")
    private String domain;

    private final WebClient webClient = WebClient.create();

    public void addNote(String ticketId, String text) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId + "/notes";

        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        webClient.post()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of(
                        "body", text,
                        "private", false
                ))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe();
    }
}
