package com.example.demo.services;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final SlackService slackService;

    // Manager to tag
    private static final String MANAGER_ID = "U09S0DD7M16";

    // Store: ChannelID -> AssignmentData
    private final Map<String, AssignmentData> pendingAssignments = new ConcurrentHashMap<>();

    // Inner class to hold tracking info
    private static class AssignmentData {
        String assignedUserTag; // e.g. <@U123>
        LocalDateTime startTime;

        public AssignmentData(String userTag) {
            this.assignedUserTag = userTag;
            this.startTime = LocalDateTime.now();
        }
    }

    // 1. Start Timer
    public void startTracking(String channelId, String userTag) {
        pendingAssignments.put(channelId, new AssignmentData(userTag));
        System.out.println("⏳ Started 1-min timer for " + userTag + " in " + channelId);
    }

    // 2. Stop Timer (Success)
    public void stopTracking(String channelId) {
        if (pendingAssignments.containsKey(channelId)) {
            pendingAssignments.remove(channelId);
            System.out.println("✅ Timer stopped for " + channelId + " (Accepted).");
        }
    }

    // 3. Background Check (Runs every 10 seconds)
    @Scheduled(fixedRate = 10000)
    public void checkSlaBreaches() {
        LocalDateTime now = LocalDateTime.now();

        for (String channelId : pendingAssignments.keySet()) {
            AssignmentData data = pendingAssignments.get(channelId);

            // Check if 1 minute has passed
            long secondsPassed = ChronoUnit.SECONDS.between(data.startTime, now);

            if (secondsPassed >= 60) {
                // ALERT!
                System.out.println("🚨 SLA Breached in " + channelId);

                String alertMsg = String.format(
                        "🚨 *Escalation:* %s has not accepted the ticket yet.\n" +
                                "👉 <@%s> Please intervene.",
                        data.assignedUserTag, MANAGER_ID
                );

                slackService.sendMessage(channelId, alertMsg);

                // Remove from tracker so we don't alert forever
                pendingAssignments.remove(channelId);
            }
        }
    }
}