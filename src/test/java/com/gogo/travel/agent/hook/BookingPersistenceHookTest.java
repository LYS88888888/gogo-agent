package com.gogo.travel.agent.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.business.booking.entity.BookingRecord;
import com.gogo.travel.business.booking.entity.BookingType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * BookingPersistenceHook 纯函数单元测试：
 * 命令模式识别（resolveOp）、CLI 入参截取（parseArgs）、容错 JSON 解析（parseJson）、
 * MCP 响应解包（unwrapMcpContent）、多 key 取值（firstString）、展示字段补全（enrichFromArgs）。
 *
 */
class BookingPersistenceHookTest {

    /** 纯函数不依赖注入字段，直接实例化即可 */
    private final BookingPersistenceHook hook = new BookingPersistenceHook();

    // ============ resolveOp ============

    @ParameterizedTest
    @CsvSource({
            "env TUNIU_API_KEY=k tuniu call flight saveOrder -a '{}', CREATE, FLIGHT",
            "tuniu call hotel tuniuHotelCreateOrder -a '{}', CREATE, HOTEL",
            "tuniu call train bookTrain -a '{}', CREATE, TRAIN",
            "tuniu call flight cancelOrder -a '{}', CANCEL, FLIGHT",
            "tuniu call train cancelOrder -a '{}', CANCEL, TRAIN"
    })
    void resolveOp_recognizesBookingCommands(String command, String action, String bizType) {
        BookingPersistenceHook.BookingOp op = hook.resolveOp(command);
        assertNotNull(op);
        assertEquals(action, op.action().name());
        assertEquals(bizType, op.bizType().name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tuniu call flight searchFlight -a '{}'",
            "tuniu call hotel searchHotel -a '{}'",
            "ls -la",
            "curl https://example.com"
    })
    void resolveOp_ignoresNonBookingCommands(String command) {
        assertNull(hook.resolveOp(command));
    }

    // ============ parseArgs ============

    @Test
    void parseArgs_extractsJsonFromCommand() {
        JSONObject args = hook.parseArgs("tuniu call flight saveOrder -a '{\"flightNo\":\"MU5101\",\"price\":1280}'");
        assertNotNull(args);
        assertEquals("MU5101", args.getString("flightNo"));
    }

    @Test
    void parseArgs_noBraces_returnsNull() {
        assertNull(hook.parseArgs("tuniu call flight cancelOrder"));
    }

    // ============ parseJson ============

    @Test
    void parseJson_pureJson() {
        JSONObject json = hook.parseJson("{\"success\":true}");
        assertNotNull(json);
        assertEquals(Boolean.TRUE, json.getBoolean("success"));
    }

    @Test
    void parseJson_jsonWrappedInLogs() {
        JSONObject json = hook.parseJson("[INFO] calling api...\n{\"orderId\":\"TN123\"}\ndone.");
        assertNotNull(json);
        assertEquals("TN123", json.getString("orderId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json at all", "   "})
    void parseJson_invalidInput_returnsNull(String text) {
        assertNull(hook.parseJson(text));
    }

    @Test
    void parseJson_null_returnsNull() {
        assertNull(hook.parseJson(null));
    }

    // ============ unwrapMcpContent ============

    @Test
    void unwrapMcpContent_extractsNestedBusinessJson() {
        JSONObject mcp = JSON.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"orderId\\\":\\\"TN456\\\"}\"}]}");
        JSONObject unwrapped = hook.unwrapMcpContent(mcp);
        assertEquals("TN456", unwrapped.getString("orderId"));
    }

    @Test
    void unwrapMcpContent_noContent_returnsOriginal() {
        JSONObject plain = JSON.parseObject("{\"orderId\":\"TN789\"}");
        assertSame(plain, hook.unwrapMcpContent(plain));
    }

    @Test
    void unwrapMcpContent_textNotJson_returnsOriginal() {
        JSONObject mcp = JSON.parseObject("{\"content\":[{\"type\":\"text\",\"text\":\"plain text\"}]}");
        assertSame(mcp, hook.unwrapMcpContent(mcp));
    }

    // ============ firstString ============

    @Test
    void firstString_returnsFirstNonBlankKey() {
        JSONObject json = JSON.parseObject("{\"orderNo\":\"\",\"mainOrderNo\":\"TN001\"}");
        assertEquals("TN001", hook.firstString(json, "orderId", "orderNo", "mainOrderNo"));
    }

    @Test
    void firstString_noneMatched_returnsNull() {
        JSONObject json = JSON.parseObject("{\"other\":\"v\"}");
        assertNull(hook.firstString(json, "orderId", "orderNo"));
        assertNull(hook.firstString(null, "orderId"));
    }

    // ============ 火车票 data 包裹穿透 ============

    /**
     * 模拟火车票 bookTrain 的完整返回链路：
     * stdout JSON → parseJson 取 result → unwrapMcpContent 解包 content[].text →
     * 穿透 data 层 → firstString 取到 orderId。
     */
    @Test
    void trainBooking_dataWrappedInMcpContent_extractsOrderId() {
        // 1. 模拟 stdout 中的外层 JSON
        JSONObject outerJson = hook.parseJson(
                "{\"success\":true,\"result\":{\"content\":[{\"type\":\"text\"," +
                        "\"text\":\"{\\\"successCode\\\":true,\\\"data\\\":{\\\"orderId\\\":1259179000," +
                        "\\\"orderDetailUrl\\\":\\\"https://m.tuniu.com/m2015/train/trainTicket/" +
                        "trainTicketOrderDetail?orderId=1259179000\\\"}}\"}]," +
                        "\"isError\":false},\"metadata\":{\"server\":\"train\",\"tool\":\"bookTrain\"}}");
        assertNotNull(outerJson);

        // 2. 取 result 层
        JSONObject result = outerJson.getJSONObject("result");
        assertNotNull(result);

        // 3. unwrapMcpContent 解包 content[].text
        JSONObject unwrapped = hook.unwrapMcpContent(result);
        assertNotNull(unwrapped);
        // 此时 orderId 在 data 内层，顶层取不到
        assertNull(unwrapped.getString("orderId"));

        // 4. 穿透 data 层
        JSONObject data = unwrapped.getJSONObject("data");
        assertNotNull(data);
        assertEquals("1259179000", hook.firstString(data, "orderId", "orderNo"));

        // 5. orderDetailUrl 也在 data 内
        assertNotNull(hook.firstString(data, "orderDetailUrl"));
    }

    /** 机票/酒店等无 data 包裹的场景，穿透逻辑应回退到原 result。 */
    @Test
    void flightBooking_noDataWrapper_fallbackToResult() {
        JSONObject result = JSON.parseObject(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"orderId\\\":\\\"TN456\\\"}\"}]}");
        JSONObject unwrapped = hook.unwrapMcpContent(result);
        JSONObject data = unwrapped != null ? unwrapped.getJSONObject("data") : null;
        JSONObject orderSource = (data != null && !data.isEmpty()) ? data : unwrapped;
        assertEquals("TN456", hook.firstString(orderSource, "orderId", "orderNo"));
    }

    // ============ enrichFromArgs ============

    @Test
    void enrichFromArgs_flight_fillsTitleTimeAndContact() {
        BookingRecord record = new BookingRecord();
        JSONObject args = JSON.parseObject("""
                {"departureCityName":"北京","arrivalCityName":"上海","flightNo":"MU5101",
                 "departureDate":"2026-08-01",
                 "contactTourist":{"name":"张三","mobile":"13812345678"}}""");

        hook.enrichFromArgs(record, BookingType.FLIGHT, args);

        assertEquals("北京→上海 MU5101", record.getTitle());
        assertNotNull(record.getStartTime());
        assertEquals("张三", record.getContactName());
        assertEquals("13812345678", record.getContactPhone());
    }

    @Test
    void enrichFromArgs_hotel_fillsCheckInOut() {
        BookingRecord record = new BookingRecord();
        JSONObject args = JSON.parseObject("""
                {"checkInDate":"2026-08-01","checkOutDate":"2026-08-03",
                 "contactName":"李四","contactPhone":"13911112222"}""");

        hook.enrichFromArgs(record, BookingType.HOTEL, args);

        assertEquals("酒店预订", record.getTitle());
        assertNotNull(record.getStartTime());
        assertNotNull(record.getEndTime());
        assertEquals("李四", record.getContactName());
    }

    @Test
    void enrichFromArgs_train_fillsPriceFromFirstResource() {
        BookingRecord record = new BookingRecord();
        JSONObject args = JSON.parseObject("""
                {"resources":[{"departsDate":"2026-08-01","adultPrice":553.5}],
                 "contact":{"tel":"13700000000"}}""");

        hook.enrichFromArgs(record, BookingType.TRAIN, args);

        assertEquals("火车票预订", record.getTitle());
        assertEquals(new BigDecimal("553.5"), record.getTotalAmount());
        assertEquals("13700000000", record.getContactPhone());
    }

    @Test
    void enrichFromArgs_nullArgs_noop() {
        BookingRecord record = new BookingRecord();
        hook.enrichFromArgs(record, BookingType.FLIGHT, null);
        assertNull(record.getTitle());
    }
}
