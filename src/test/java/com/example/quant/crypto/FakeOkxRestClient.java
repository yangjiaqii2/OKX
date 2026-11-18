package com.example.quant.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 测试用 OkxRestClient —— 子类化 + override publicGet，注入 canned responses。
 *
 * <p>使用方式：<pre>
 *   FakeOkxRestClient fake = new FakeOkxRestClient();
 *   fake.respondToPath("/api/v5/rubik/stat/contracts/open-interest-history",
 *                      "{...okx oi json...}");
 *   fake.respondToDefault("[...]");  // 默认响应
 *   new OkxOpenInterestHistoryProvider("okx", fake).fetch("BTC-USDT-SWAP");
 * </pre>
 *
 * <p>支持异常注入：{@link #throwOnPublicGet(RuntimeException)}。
 */
public class FakeOkxRestClient extends OkxRestClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Deque<String>> pathResponses = new HashMap<>();
    private String defaultResponse = "{\"code\":\"0\",\"data\":[]}";
    private RuntimeException throwOnPublicGet;

    public FakeOkxRestClient() {
        super();
    }

    /** 为指定 path 添加 canned response（FIFO 队列）。 */
    public void respondToPath(String path, String jsonResponse) {
        pathResponses.computeIfAbsent(path, k -> new ArrayDeque<>()).add(jsonResponse);
    }

    /** 设置默认响应（path 未匹配时使用）。 */
    public void respondToDefault(String jsonResponse) {
        this.defaultResponse = jsonResponse;
    }

    /** 设置 publicGet 抛出的异常。 */
    public void throwOnPublicGet(RuntimeException ex) {
        this.throwOnPublicGet = ex;
    }

    /** 获取本测试中已调用的 path 列表（按调用顺序）。 */
    public java.util.List<String> getCalledPaths() {
        return new java.util.ArrayList<>(calledPaths);
    }

    private final java.util.List<String> calledPaths = new java.util.ArrayList<>();

    @Override
    public JsonNode publicGet(String requestPath, java.time.Duration timeout) {
        return publicGet(requestPath);
    }

    @Override
    protected JsonNode publicGetWithoutHttpClient(String requestPath) {
        return publicGet(requestPath);
    }

    @Override
    public JsonNode publicGet(String requestPath) {
        calledPaths.add(requestPath);
        if (throwOnPublicGet != null) {
            throw throwOnPublicGet;
        }
        Deque<String> queue = pathResponses.get(requestPath);
        String response;
        if (queue != null && !queue.isEmpty()) {
            response = queue.poll();
        } else {
            response = defaultResponse;
        }
        try {
            return mapper.readTree(response);
        } catch (Exception e) {
            throw new RuntimeException("FakeOkxRestClient: failed to parse canned response: " + e.getMessage(), e);
        }
    }
}
