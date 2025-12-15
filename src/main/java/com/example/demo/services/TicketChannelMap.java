package com.example.demo.services;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TicketChannelMap {

    private final Map<String, String> channelToTicket = new ConcurrentHashMap<>();
    private final Map<String, String> ticketToChannel = new ConcurrentHashMap<>();

    // save mapping
    public void put(String ticketId, String channelId) {
        ticketToChannel.put(ticketId, channelId);
        channelToTicket.put(channelId, ticketId);
    }

    public String getChannelId(String ticketId) {
        return ticketToChannel.get(ticketId);
    }

    public String getTicketId(String channelId) {
        return channelToTicket.get(channelId);
    }

    public void remove(String ticketId) {
        String channelId = ticketToChannel.remove(ticketId);
        if (channelId != null) {
            channelToTicket.remove(channelId);
        }
    }
}

