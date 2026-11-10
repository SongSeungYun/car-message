package com.hyundaiproject.messaging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class MessageCatalog {
    enum Page { HOME, MAIN, ADDITIONAL, TURN }

    record Preset(int number, String code, String text) {}

    private static final List<Preset> MAIN = Arrays.asList(
            new Preset(1, "road_incident", "전방 특이사항 발생"),
            new Preset(2, "vehicle_breakdown", "차량 고장"),
            new Preset(3, "reversing", "후진합니다"),
            new Preset(4, "help", "도와주세요"),
            new Preset(5, "passenger_exit", "사람 하차합니다"));

    private static final List<Preset> ADDITIONAL = Arrays.asList(
            new Preset(6, "stopped_briefly", "잠시 정차 중 입니다"),
            new Preset(7, "thanks", "감사합니다"),
            new Preset(8, "sorry", "죄송합니다"),
            new Preset(9, "parking", "주차 중 입니다"),
            new Preset(10, "driving_slowly", "서행합니다"));

    private static final List<Preset> TURN = Arrays.asList(
            new Preset(1, "turn_left", "좌회전"),
            new Preset(2, "u_turn", "유턴합니다"),
            new Preset(3, "departing", "출발합니다"));

    static List<Preset> presets(Page page) {
        return switch (page) {
            case HOME -> Collections.emptyList();
            case MAIN -> MAIN;
            case ADDITIONAL -> ADDITIONAL;
            case TURN -> TURN;
        };
    }

    private MessageCatalog() {}
}
