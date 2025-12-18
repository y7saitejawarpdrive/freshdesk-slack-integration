package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
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
    private final TicketChannelMap map;
    private final ObjectMapper objectMapper;

    public SlackInteractionController(FreshdeskService freshdeskService, SlackService slackService, TicketChannelMap map) {
        this.freshdeskService = freshdeskService;
        this.slackService = slackService;
        this.map = map;
        this.objectMapper = new ObjectMapper();
    }

    @PostMapping(value = "/interactions", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> handleInteractions(@RequestParam("payload") String payloadJson) {
        try {
            Map<String, Object> payload = objectMapper.readValue(payloadJson, Map.class);
            String responseUrl = payload.get("response_url").toString();
            List<Map<String, Object>> actions = (List<Map<String, Object>>) payload.get("actions");

            Map<String, Object> user = (Map<String, Object>) payload.get("user");
            String managerName = user.get("username").toString();

            if (actions != null && !actions.isEmpty()) {
                Map<String, Object> action = actions.get(0);
                String actionId = action.get("action_id").toString();

                if (actionId.startsWith("assign_")) {
                    String ticketId = actionId.split("_")[1];
                    String selectedUserId = ((Map) action.get("selected_user")).toString();

                    String channelName = "ticket-" + ticketId; // Simple name
                    String channelId = slackService.createChannel(channelName);

                    if (channelId != null) {
                        map.put(ticketId, channelId);
                        slackService.inviteUserToChannel(channelId, selectedUserId);
                        slackService.sendMessage(channelId, "👋 Welcome <@" + selectedUserId + ">! Ticket #" + ticketId + " Assigned.");
                        slackService.markTriageAsAssigned(responseUrl, ticketId, selectedUserId, channelId);
                    }
                }
                else if (actionId.startsWith("approve_")) {
                    String ticketId = actionId.split("_")[1];
                    freshdeskService.addNote(ticketId, "✅ Approved by " + managerName);
                    slackService.updateInteractionMessage(responseUrl, "✅ *Approved by " + managerName + "*");
                }
                else if (actionId.startsWith("reject_")) {
                    String ticketId = actionId.split("_")[1];
                    freshdeskService.addNote(ticketId, "❌ Rejected by " + managerName);
                    slackService.updateInteractionMessage(responseUrl, ":x: *Rejected by " + managerName + "*");
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return ResponseEntity.ok().build();
    }
}