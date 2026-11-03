package dev.pocketengine.runtime.models;

import org.json.JSONObject;
import org.json.JSONTokener;

/** Bounded, content-free explanation. Provider prose never becomes advice, a route or retry authority. */
public final class ModelConnectionFailure {
    private ModelConnectionFailure() { }
    public enum Reason { UNSUPPORTED_MODEL, ENDPOINT_MISMATCH, AUTHENTICATION, NOT_FOUND, RATE_LIMIT, PARAMETERS, SERVER, REDIRECT, UNKNOWN }
    public static Reason reason(int status, String body) {
        if (status == 400 || status == 404 || status == 422) {
            String code = exactCode(body);
            if (java.util.Set.of("unsupported_endpoint", "endpoint_mismatch", "wrong_endpoint", "unsupported_protocol").contains(code)) return Reason.ENDPOINT_MISMATCH;
            if (java.util.Set.of("unsupported_model", "model_not_supported").contains(code)) return Reason.UNSUPPORTED_MODEL;
        }
        if (status == 401 || status == 403) return Reason.AUTHENTICATION;
        if (status == 404) return Reason.NOT_FOUND;
        if (status == 429) return Reason.RATE_LIMIT;
        if (status == 400 || status == 422) return Reason.PARAMETERS;
        if (status >= 500) return Reason.SERVER;
        if (status >= 300 && status < 400) return Reason.REDIRECT;
        return Reason.UNKNOWN;
    }
    public static String message(Reason reason) {
        return switch (reason) {
            case UNSUPPORTED_MODEL -> "服务商明确拒绝此模型；请核对模型 ID 与当前接口协议。可能需要更换协议或模型，不能据此判断账号未激活。";
            case ENDPOINT_MISMATCH -> "服务商明确报告接口协议不匹配；请核对连接中的协议和最终接口地址，再明确选择模型。";
            case AUTHENTICATION -> "模型认证失败，请核对原连接的 API 密钥与账号权限。";
            case NOT_FOUND -> "找不到模型或接口地址，请核对精确模型 ID 与接口路径。";
            case RATE_LIMIT -> "服务商限制了当前请求；请核对原账号的额度或请求频率，稍后自行重试。";
            case PARAMETERS -> "服务商未接受请求，请核对模型、输出与推理参数，以及工具或图片支持。";
            case SERVER -> "模型服务暂时不可用，请保留当前连接，稍后自行重试。";
            case REDIRECT -> "模型地址发生重定向，请在原连接填写服务提供方的最终接口地址。";
            case UNKNOWN -> "本次错误原因尚未确认，请核对原连接和网络；配置仍保留。";
        };
    }
    private static String exactCode(String body) {
        if (body == null || body.length() > 16384) return "";
        try {
            JSONTokener parser = new JSONTokener(body); Object parsed = parser.nextValue();
            if (!(parsed instanceof JSONObject root) || parser.nextClean() != 0) return "";
            JSONObject error = root.optJSONObject("error"); Object code = error == null ? null : error.opt("code");
            return code instanceof String value && value.matches("[a-z_]{1,64}") ? value : "";
        } catch (Exception invalid) { return ""; }
    }
}
