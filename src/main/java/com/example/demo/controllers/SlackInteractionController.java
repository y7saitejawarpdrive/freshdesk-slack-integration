package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.SlackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
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
    public void handleInteractions(@RequestParam("payload") String payloadJson) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            List<Map<String, Object>> actions = (List<Map<String, Object>>) payload.get("actions");
            Map<String, Object> channel = (Map<String, Object>) payload.get("channel");
            String channelId = channel.get("id").toString();
            Map<String, Object> user = (Map<String, Object>) payload.get("user");
            String managerName = user.get("username").toString();

            if (actions != null && !actions.isEmpty()) {
                String actionId = actions.get(0).get("action_id").toString();

                if (actionId.startsWith("approve_")) {
                    String ticketId = actionId.split("_")[1];
                    freshdeskService.addNote(ticketId, "✅ Reroute Approved by " + managerName);
                    slackService.sendMessage(channelId, "✅ *Approved by " + managerName + "*\nReroute initiated.");
                }
                else if (actionId.startsWith("reject_")) {
                    String ticketId = actionId.split("_")[1];
                    freshdeskService.addNote(ticketId, "❌ Rejected by " + managerName + ". Asking for alternative.");

                    String rejectMsg = String.format(
                            ":x: *Proposal Rejected by %s*\n" +
                                    "The proposed delay is not acceptable.\n" +
                                    "👉 *Please propose an alternate route or solution immediately.*",
                            managerName
                    );
                    slackService.sendMessage(channelId, rejectMsg);
                }
            }
        } catch (Exception e) {
            System.out.println("Error handling interaction: " + e.getMessage());
        }
    }
}