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

    public void addNote(String ticketId, String text) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId + "/notes";
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        webClient.post()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .bodyValue(Map.of("body", text, "private", false))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    public void addNoteWithFile(String ticketId, String text, String fileName, byte[] fileData) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId + "/notes";
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("body", text);
        builder.part("private", "false");
        builder.part("attachments[]", new ByteArrayResource(fileData) {
            @Override public String getFilename() { return fileName; }
        }, MediaType.APPLICATION_OCTET_STREAM);

        webClient.post()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve().bodyToMono(String.class).subscribe();
    }

    public void updateTicketFields(String ticketId, Map<String, Object> fields) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId;
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        webClient.put()
                .uri(url)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .bodyValue(fields)
                .retrieve().bodyToMono(String.class).subscribe();
    }

    // --- NEW: Get SLA from Ticket Custom Fields ---
    public int getTicketSlaHours(String ticketId) {
        String url = "https://" + domain + "/api/v2/tickets/" + ticketId;
        String auth = Base64.getEncoder().encodeToString((apiKey + ":X").getBytes());

        try {
            Map response = webClient.get()
                    .uri(url)
                    .header("Authorization", "Basic " + auth)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null) {
                Map customFields = (Map) response.get("custom_fields");
                if (customFields != null && customFields.containsKey("cf_sla")) {
                    String slaString = customFields.get("cf_sla").toString(); // e.g. "8 hrs"
                    String numberOnly = slaString.replaceAll("[^0-9]", "");
                    return numberOnly.isEmpty() ? 0 : Integer.parseInt(numberOnly);
                }
            }
        } catch (Exception e) {
            System.out.println("⚠ Error fetching SLA: " + e.getMessage());
        }
        return 0; // Default
    }
}