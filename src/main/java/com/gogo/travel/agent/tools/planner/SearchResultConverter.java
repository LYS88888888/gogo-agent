package com.gogo.travel.agent.tools.planner;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.gogo.travel.agent.tools.store.SearchCandidateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
/**
 * 搜索结果转换器：从 Redis 读取搜索阶段捕获的候选数据，转换为规划引擎所需的 candidates JSON。
 *
 * <p>从 ItineraryPlannerTool 抽取，便于独立单元测试和数据源扩展。
 *
 */
public class SearchResultConverter {

    private static final Logger logger = LoggerFactory.getLogger(SearchResultConverter.class);

    private final SearchCandidateStore searchCandidateStore;

    public SearchResultConverter(SearchCandidateStore searchCandidateStore) {
        this.searchCandidateStore = searchCandidateStore;
    }

    /**
     * 根据出发地/目的地/日期精确拼接 Redis hash field 名称，通过 HMGET 精准查询候选数据。
     *
     * @param userId        用户 ID
     * @param origin        出发地城市（必填）
     * @param destination   目的地城市（必填）
     * @param departureDate 去程日期（必填，YYYY-MM-DD）
     * @param returnDate    返程日期（必填，YYYY-MM-DD）
     * @return candidates JSON，无数据时返回 null
     */
    public JSONObject buildCandidates(String userId, String origin, String destination,
                                      String departureDate, String returnDate) {
        logger.info("[plan_itinerary] buildCandidates 开始, userId={}, origin={}, destination={}, departureDate={}, returnDate={}",
                userId, origin, destination, departureDate, returnDate);

        JSONArray transportOptions = new JSONArray();
        JSONArray hotelOptions = new JSONArray();
        int[] tCounter = {0};
        int[] hCounter = {0};

        // 精确查询：由 Store 根据行程规则拼接 field 名称并 HMGET
        Map<String, String> allEntries = searchCandidateStore.loadByTrip(userId, origin, destination, departureDate, returnDate);
        logger.info("[plan_itinerary] buildCandidates: 精确查询命中 {} 条", allEntries.size());
        processEntries(allEntries, transportOptions, hotelOptions, tCounter, hCounter, departureDate, returnDate);

        logger.info("[plan_itinerary] buildCandidates 完成: transport_options={}, hotel_options={}", transportOptions.size(), hotelOptions.size());
        if (transportOptions.isEmpty() && hotelOptions.isEmpty()) {
            logger.info("[plan_itinerary] buildCandidates: 交通和酒店候选均为空, 返回 null");
            return null;
        }

        JSONObject candidates = new JSONObject();
        candidates.put("transport_options", transportOptions);
        candidates.put("hotel_options", hotelOptions);
        return candidates;
    }

    /**
     * 处理一批同类型的 Redis 条目，解析后分派到对应的转换方法。
     */
    private void processEntries(Map<String, String> entries,
                                JSONArray transportOptions, JSONArray hotelOptions,
                                int[] tCounter, int[] hCounter,
                                String departureDate, String returnDate) {
        if (entries.isEmpty()) {
            return;
        }
        // 按 field 名排序，保证同一数据在不同调用中生成一致的候选 ID
        List<String> sortedKeys = new ArrayList<>(entries.keySet());
        sortedKeys.sort(Comparator.naturalOrder());

        for (String key : sortedKeys) {
            String value = entries.get(key);
            JSONObject entry;
            try {
                entry = JSON.parseObject(value);
            } catch (Exception e) {
                logger.warn("[plan_itinerary] 解析搜索结果失败, skip, key={}", key, e);
                continue;
            }

            String type = entry.getString("type");
            JSONObject args = entry.getJSONObject("args");
            JSONObject data = entry.getJSONObject("data");
            if (type == null || data == null) {
                logger.info("[plan_itinerary] buildCandidates: key={} 的 type 或 data 为空, 跳过 (type={}, data={})", key, type, data);
                continue;
            }
            logger.info("[plan_itinerary] buildCandidates: 处理 key={}, type={}", key, type);

            switch (type) {
                case "FLIGHT" -> convertFlights(data, args, transportOptions, tCounter);
                case "TRAIN" -> convertTrains(data, args, transportOptions, tCounter);
                case "HOTEL" -> convertHotels(data, args, hotelOptions, hCounter, departureDate, returnDate);
                default -> logger.warn("[plan_itinerary] 未知搜索类型 {}", type);
            }
        }
    }

    /**
     * 将 tuniu 机票搜索结果转换为 transport_options 条目。
     */
    private void convertFlights(JSONObject data, JSONObject args, JSONArray transportList, int[] counter) {
        JSONArray flights = data.getJSONArray("data");
        if (flights == null) {
            logger.info("[plan_itinerary] convertFlights: data 数组为空, 跳过");
            return;
        }
        logger.info("[plan_itinerary] convertFlights: 待转换机票数量={}", flights.size());
        String depCity = args != null ? args.getString("departureCityName") : null;
        String arrCity = args != null ? args.getString("arrivalCityName") : null;

        for (int i = 0; i < flights.size(); i++) {
            JSONObject f = flights.getJSONObject(i);
            JSONObject t = new JSONObject();
            t.put("id", "F" + (++counter[0]));
            t.put("type", "flight");
            t.put("departure_time", f.getString("departureTime"));
            t.put("arrival_time", f.getString("arrivalTime"));
            t.put("origin", depCity != null ? depCity : f.getString("departureAirport"));
            t.put("destination", arrCity != null ? arrCity : f.getString("arrivalAirport"));
            double base = PlannerUtils.parseDoubleSafe(f.getString("basePrice"));
            double tax = PlannerUtils.parseDoubleSafe(f.getString("totalTax"));
            t.put("price", PlannerUtils.round2(base + tax));
            t.put("cabin_class", f.getString("cabinClass"));
            t.put("is_direct", "直飞".equals(f.getString("type")));
            t.put("refund_policy", "不可退");
            t.put("carrier", f.getString("airlineCompany"));
            t.put("code", f.getString("flightNumber"));
            transportList.add(t);
        }
    }

    /**
     * 将 tuniu 火车票搜索结果转换为 transport_options 条目。
     *
     * <p>每趟列车按座位等级展开为多条候选（二等座/一等座/商务座）。
     */
    private void convertTrains(JSONObject data, JSONObject args, JSONArray transportList, int[] counter) {
        JSONArray trains = data.getJSONArray("data");
        if (trains == null) {
            logger.info("[plan_itinerary] convertTrains: data 数组为空, 跳过");
            return;
        }
        logger.info("[plan_itinerary] convertTrains: 待转换火车数量={}", trains.size());
        String depCity = args != null ? args.getString("departureCityName") : null;
        String arrCity = args != null ? args.getString("arrivalCityName") : null;

        for (int i = 0; i < trains.size(); i++) {
            JSONObject tr = trains.getJSONObject(i);
            JSONObject price = tr.getJSONObject("price");
            JSONObject seatAvail = tr.getJSONObject("seatAvailable");
            if (price == null) {
                continue;
            }

            String[] seatClasses = {"edz", "ydz", "swz"};
            String[] seatLabels = {"二等座", "一等座", "商务座"};

            for (int s = 0; s < seatClasses.length; s++) {
                String priceKey = seatClasses[s] + "Price";
                String numKey = seatClasses[s] + "Num";
                String priceStr = price.getString(priceKey);
                if (priceStr == null || priceStr.isBlank()) {
                    continue;
                }
                Integer avail = seatAvail != null ? PlannerUtils.getInt(seatAvail, numKey) : null;
                if (avail != null && avail == 0) {
                    continue;
                }

                JSONObject t = new JSONObject();
                t.put("id", "T" + (++counter[0]));
                t.put("type", "train");
                t.put("departure_time", tr.getString("departureTime"));
                t.put("arrival_time", tr.getString("arrivalTime"));
                t.put("origin", depCity != null ? depCity : tr.getString("departStationName"));
                t.put("destination", arrCity != null ? arrCity : tr.getString("destStationName"));
                t.put("price", PlannerUtils.round2(PlannerUtils.parseDoubleSafe(priceStr)));
                t.put("cabin_class", seatLabels[s]);
                t.put("is_direct", "direct".equals(tr.getString("trainType")));
                t.put("refund_policy", "可退");
                t.put("carrier", tr.getString("trainNum"));
                t.put("code", tr.getString("trainNum"));
                transportList.add(t);
            }
        }
    }

    /**
     * 将 tuniu 酒店搜索结果转换为 hotel_options 条目。
     */
    private void convertHotels(JSONObject data, JSONObject args, JSONArray hotelList, int[] counter,
                               String departureDate, String returnDate) {
        JSONArray hotels = data.getJSONArray("hotels");
        if (hotels == null) {
            logger.info("[plan_itinerary] convertHotels: hotels 数组为空, 跳过");
            return;
        }
        logger.info("[plan_itinerary] convertHotels: 待转换酒店数量={}", hotels.size());

        int nights = computeNights(args, departureDate, returnDate);
        logger.info("[plan_itinerary] convertHotels: 计算入住晚数={}", nights);

        for (int i = 0; i < hotels.size(); i++) {
            JSONObject h = hotels.getJSONObject(i);
            JSONObject entry = new JSONObject();
            entry.put("id", "H" + (++counter[0]));
            entry.put("name", h.getString("hotelName"));
            entry.put("brand", h.getString("brandName"));
            entry.put("star_rating", parseStarRating(h.getString("starName")));
            entry.put("room_type", h.getString("roomName"));
            entry.put("price_per_night", PlannerUtils.getDblOr(h, "lowestPrice", 0));
            entry.put("nights", nights);
            entry.put("distance_to_dest_km", 0);
            String meal = h.getString("meal");
            entry.put("breakfast_included", meal != null && meal.contains("早餐"));
            entry.put("cancel_policy", h.getString("refund"));
            hotelList.add(entry);
        }
    }

    /** 酒店星级映射：豪华型→5, 高档型→4, 舒适型→3, 经济型→2, 默认→3 */
    private static int parseStarRating(String starName) {
        if (starName == null) {
            return 3;
        }
        if (starName.contains("豪华")) {
            return 5;
        }
        if (starName.contains("高档")) {
            return 4;
        }
        if (starName.contains("舒适")) {
            return 3;
        }
        if (starName.contains("经济")) {
            return 2;
        }
        return 3;
    }

    /** 从酒店搜索参数计算入住晚数 */
    private static int computeNights(JSONObject args, String departureDate, String returnDate) {
        if (args != null) {
            String checkIn = args.getString("checkInDate");
            if (checkIn == null) {
                checkIn = args.getString("checkIn");
            }
            String checkOut = args.getString("checkOutDate");
            if (checkOut == null) {
                checkOut = args.getString("checkOut");
            }
            if (checkIn != null && checkOut != null) {
                try {
                    LocalDate ci = LocalDate.parse(checkIn.trim());
                    LocalDate co = LocalDate.parse(checkOut.trim());
                    long n = ChronoUnit.DAYS.between(ci, co);
                    return (int) Math.max(1, n);
                } catch (Exception ignored) {
                    // fall through
                }
            }
        }
        if (departureDate != null && returnDate != null) {
            try {
                LocalDate dep = LocalDate.parse(departureDate.trim());
                LocalDate ret = LocalDate.parse(returnDate.trim());
                long n = ChronoUnit.DAYS.between(dep, ret);
                return (int) Math.max(1, n);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return 1;
    }
}
