package com.yamu.be.global.calendar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 공공데이터포털 「한국천문연구원_특일 정보」의 공휴일 조회({@code getRestDeInfo})를 부른다.
 *
 * <pre>
 * 요청: GET {baseUrl}/getRestDeInfo?ServiceKey=..&solYear=2026&solMonth=05&numOfRows=100
 * 성공: &lt;response&gt;&lt;header&gt;&lt;resultCode&gt;00&lt;/resultCode&gt;..&lt;/header&gt;
 *       &lt;body&gt;&lt;items&gt;&lt;item&gt;&lt;dateName&gt;어린이날&lt;/dateName&gt;&lt;isHoliday&gt;Y&lt;/isHoliday&gt;&lt;locdate&gt;20260505&lt;/locdate&gt;..
 *       &lt;/item&gt;&lt;/items&gt;&lt;totalCount&gt;2&lt;/totalCount&gt;&lt;/body&gt;&lt;/response&gt;
 * 키 오류 등: HTTP 200이어도 &lt;OpenAPI_ServiceResponse&gt;&lt;cmmMsgHeader&gt;..&lt;returnReasonCode&gt;30&lt;/returnReasonCode&gt;
 * </pre>
 *
 * <p>응답은 XML로만 읽는다. JSON은 항목이 하나면 배열이 아니라 객체로 와서 모양이 달라진다.
 * 한 해는 1~12월을 따로 불러 합치고, 한 달이라도 실패하면 그 해 전체를 실패로 본다(빠진 달을 영업일로 세지 않게).
 * 조회만 하므로 결과 불명을 따로 다루지 않는다. 자동으로 다시 부르지 않고 다음 동기화를 기다린다.
 *
 * <p>서비스 키는 요청 주소에 들어간다. 그래서 RestClient 예외(메시지에 주소가 들어 있다)를 그대로 올리거나 원인으로 붙이지 않고,
 * 연도·월·결과 코드·예외 종류만 담은 {@link HolidayApiException}으로 바꾼다. 응답 본문도 로그에 남기지 않는다.
 */
@Slf4j
public class HolidayApiClient {

    private static final String SUCCESS = "00";
    private static final String HOLIDAY = "Y";
    /** 한 달 공휴일은 많아야 대여섯 개라 한 쪽으로 다 받는다. 그래도 totalCount가 더 크면 실패로 본다. */
    private static final int ROWS = 100;
    private static final DateTimeFormatter LOCDATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final HolidayApiProperties properties;

    public HolidayApiClient(RestClient restClient, HolidayApiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        if (properties.hasServiceKey() && isEncoded(properties.serviceKey())) {
            log.warn("yamu.holiday.service-key에 인코딩 키가 들어 있어 한 번 풀어서 씁니다. 디코딩 키로 바꾸는 것을 권장합니다(docs/be/holiday.md)");
        }
    }

    /**
     * 그 해 공휴일(일요일과 겹친 날 포함). 아직 발표 전인 해는 비어 있다.
     * 같은 날 공휴일이 둘이면(예: 어린이날·부처님오신날) 이름을 합쳐 하나로 둔다.
     *
     * @throws HolidayApiException 한 달이라도 부르지 못했거나 응답을 믿을 수 없을 때
     */
    public Map<LocalDate, String> fetchYear(int year) {
        Map<LocalDate, String> holidays = new TreeMap<>();
        for (int month = 1; month <= 12; month++) {
            fetchMonth(year, month).forEach((date, name) -> holidays.merge(date, name, HolidayApiClient::joinNames));
        }
        return holidays;
    }

    Map<LocalDate, String> fetchMonth(int year, int month) {
        if (!properties.hasServiceKey()) {
            throw new HolidayApiException("서비스 키가 없습니다(yamu.holiday.service-key)");
        }
        return parse(call(year, month, decodedKey(year, month)), year, month);
    }

    /**
     * 포털은 같은 키를 디코딩·인코딩 두 가지로 보여 준다. 인코딩 키를 그대로 다시 인코딩하면 %가 %25가 되어 403이 나므로,
     * %가 있으면 인코딩 키로 보고 한 번 푼다. 디코딩 키(영문·숫자·+·/·=)에는 %가 나오지 않아 둘을 헷갈리지 않는다.
     */
    private String decodedKey(int year, int month) {
        String key = properties.serviceKey().strip();
        if (!isEncoded(key)) {
            return key;
        }
        try {
            // URLDecoder와 달리 +를 공백으로 바꾸지 않는다
            return UriUtils.decode(key, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw failure(year, month, "서비스 키의 % 인코딩이 깨져 있습니다(yamu.holiday.service-key)");
        }
    }

    private static boolean isEncoded(String key) {
        return key.indexOf('%') >= 0;
    }

    private byte[] call(int year, int month, String key) {
        // encode()를 build 전에 부르면 변수 값은 +·/·=까지 모두 퍼센트 인코딩된다. 디코딩 키를 여기서 한 번만 인코딩한다.
        URI uri = UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/getRestDeInfo")
                .queryParam("ServiceKey", "{key}")
                .queryParam("solYear", year)
                .queryParam("solMonth", "%02d".formatted(month))
                .queryParam("numOfRows", ROWS)
                .encode()
                .buildAndExpand(Map.of("key", key))
                .toUri();
        try {
            byte[] body = restClient.get().uri(uri).retrieve().body(byte[].class);
            if (body == null || body.length == 0) {
                throw failure(year, month, "응답 본문이 없습니다");
            }
            return body;
        } catch (RestClientResponseException e) {
            throw failure(year, month, "HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw failure(year, month, "연결 실패 " + e.getMostSpecificCause().getClass().getSimpleName());
        } catch (RestClientException e) {
            throw failure(year, month, e.getClass().getSimpleName());
        }
    }

    private static Map<LocalDate, String> parse(byte[] body, int year, int month) {
        Element root = readXml(body, year, month).getDocumentElement();
        if (!"response".equals(root.getTagName())) {
            // 게이트웨이 오류(키 미등록·호출 한도 초과 등). 사유 코드만 남긴다
            throw failure(year, month, "오류 응답 " + root.getTagName() + " returnReasonCode=" + text(root, "returnReasonCode"));
        }
        String resultCode = text(root, "resultCode");
        if (!SUCCESS.equals(resultCode)) {
            throw failure(year, month, "resultCode=" + resultCode);
        }

        NodeList items = root.getElementsByTagName("item");
        int totalCount = parseCount(text(root, "totalCount"), year, month);
        if (totalCount != items.getLength()) {
            throw failure(year, month, "totalCount " + totalCount + "와 받은 항목 " + items.getLength() + "개가 다릅니다");
        }

        Map<LocalDate, String> holidays = new TreeMap<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (!HOLIDAY.equals(text(item, "isHoliday"))) {
                continue;
            }
            LocalDate date = parseDate(text(item, "locdate"), year, month);
            String name = text(item, "dateName");
            if (name == null || name.isBlank()) {
                throw failure(year, month, "이름이 빠진 공휴일 " + date);
            }
            holidays.merge(date, name.strip(), HolidayApiClient::joinNames);
        }
        return holidays;
    }

    private static Document readXml(byte[] body, int year, int month) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 외부 엔티티(XXE)를 막는다. 공공 API 응답이지만 남이 보낸 XML이다
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 기본 오류 처리기는 응답 내용 일부를 표준 오류로 찍는다. 찍지 않고 예외로만 받는다
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw failure(year, month, "XML이 아닌 응답 " + e.getClass().getSimpleName());
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
    }

    private static int parseCount(String value, int year, int month) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw failure(year, month, "totalCount가 숫자가 아닙니다");
        }
    }

    private static LocalDate parseDate(String locdate, int year, int month) {
        LocalDate date;
        try {
            date = LocalDate.parse(locdate == null ? "" : locdate, LOCDATE);
        } catch (DateTimeParseException e) {
            throw failure(year, month, "locdate 형식이 다릅니다");
        }
        if (date.getYear() != year || date.getMonthValue() != month) {
            throw failure(year, month, "요청하지 않은 달의 날짜 " + date);
        }
        return date;
    }

    private static String joinNames(String a, String b) {
        return a.equals(b) ? a : a + "·" + b;
    }

    private static HolidayApiException failure(int year, int month, String reason) {
        return new HolidayApiException("공휴일 API 실패 " + year + "-%02d: ".formatted(month) + reason);
    }
}
