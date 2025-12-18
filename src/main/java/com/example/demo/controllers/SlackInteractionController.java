package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.SlackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/slack")
public class SlackInteractionController {

    private final FreshdeskService freshdeskService;
    private final SlackService slackService;
    private final ObjectMapper objectMapper;

    public SlackInteractionController(FreshdeskService freshdeskService, SlackService slackService) {
        this.freshdeskService = freshdeskService;
        this.slackService = slackService;
        this.objectMapper = new ObjectMapper();
    }

    @PostMapping(value = "/interactions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> handleInteractions(@RequestParam("payload") String payloadJson) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);

            // 1. Get the Response URL (The magic key to update the message)
            String responseUrl = payload.get("response_url").toString();

            List<Map<String, Object>> actions = (List<Map<String, Object>>) payload.get("actions");
            Map<String, Object> user = (Map<String, Object>) payload.get("user");
            String managerName = user.get("username").toString();

            if (actions != null && !actions.isEmpty()) {
                String actionId = actions.get(0).get("action_id").toString();
                String ticketId = actionId.split("_")[1];

                if (actionId.startsWith("approve_")) {
                    System.out.println("✅ Approved by " + managerName);

                    // Update Freshdesk
                    freshdeskService.addNote(ticketId, "✅ Reroute Approved by " + managerName);

                    // Update Slack (REMOVE BUTTONS INSTANTLY)
                    slackService.updateInteractionMessage(responseUrl, "✅ *Reroute Approved by " + managerName + "*");
                }
                else if (actionId.startsWith("reject_")) {
                    System.out.println("❌ Rejected by " + managerName);

                    // Update Freshdesk
                    freshdeskService.addNote(ticketId, "❌ Rejected by " + managerName + ". Asking for alternative.");

                    // Update Slack (REMOVE BUTTONS INSTANTLY)
                    String msg = String.format(":x: *Rejected by %s*\nPlease propose an alternative.", managerName);
                    slackService.updateInteractionMessage(responseUrl, msg);
                }
            }
        } catch (Exception e) {
            System.out.println("Error handling interaction: " + e.getMessage());
        }
        // Return 200 OK immediately to tell Slack "We got it"
        return ResponseEntity.ok().build();
    }
}