package com.hyundaiproject.messaging;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class MessageCatalog {
    enum Page { HOME, MAIN, ADDITIONAL, TURN }

    record Preset(int number, String code, String text) {}

    // code·text는 docs/messages.json과 같아야 한다 (서버는 code만 넘기고, 받는 차가 여기서 문구를 찾는다)
    private static final List<Preset> MAIN = Arrays.asList(
            new Preset(1, "FRONT_INCIDENT", "전방 특이사항 발생"),
            new Preset(2, "BREAKDOWN", "차량 고장"),
            new Preset(3, "REVERSING", "후진합니다"),
            new Preset(4, "HELP", "도와주세요"),
            new Preset(5, "PASSENGER_EXIT", "사람 하차합니다"));

    private static final List<Preset> ADDITIONAL = Arrays.asList(
            new Preset(6, "BRIEF_STOP", "잠시 정차 중입니다"),
            new Preset(7, "THANKS", "감사합니다"),
            new Preset(8, "SORRY", "죄송합니다"),
            new Preset(9, "PARKING", "주차 중입니다"),
            new Preset(10, "SLOW_DOWN", "서행합니다"));

    private static final List<Preset> TURN = Arrays.asList(
            new Preset(1, "LEFT_TURN", "좌회전합니다"),
            new Preset(2, "U_TURN", "유턴합니다"),
            new Preset(3, "DEPART", "출발합니다"));

    /** 받은 code의 문구. 코드표에 없으면 null (protocol.md: 모르는 코드는 무시) */
    static Preset find(String code) {
        for (List<Preset> list : List.of(MAIN, ADDITIONAL, TURN)) {
            for (Preset preset : list) if (preset.code().equals(code)) return preset;
        }
        return null;
    }

    /** 비상등 주요 메시지(1~5번)만 빨간 경고로 받는다 */
    static boolean critical(String code) {
        for (Preset preset : MAIN) if (preset.code().equals(code)) return true;
        return false;
    }

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
