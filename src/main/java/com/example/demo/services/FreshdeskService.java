package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FreshdeskService {

    @Value("${freshdesk.api.key}")
    private String apiKey;

    @Value("${freshdesk.domain}")
    private String domain;

    private final WebClient webClient = WebClient.create();

    // 1. Standard Text Note
    public void addNote(String ticketId, String text) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId + "/notes";
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        webClient.post()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("body", text, "private", false))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe();
    }

    // 2. NEW: Upload File to Freshdesk
    public void addNoteWithFile(String ticketId, String text, String fileName, byte[] fileData) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId + "/notes";
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        // Build the Multipart Request
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("body", text);
        builder.part("private", "false");

        // Attach the file
        builder.part("attachments[]", new ByteArrayResource(fileData) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, MediaType.APPLICATION_OCTET_STREAM);

        webClient.post()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        success -> System.out.println("✅ File uploaded to Ticket " + ticketId),
                        error -> System.out.println("❌ File upload failed: " + error.getMessage())
                );
    }
}