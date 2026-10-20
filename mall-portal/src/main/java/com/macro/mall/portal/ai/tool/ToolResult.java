package com.macro.mall.portal.ai.tool;

/**
 * AI 工具的统一返回结构。
 *
 * ============================ 为什么工具要返回这个，而不是直接返回业务对象 ============================
 *
 * 【核心认知】工具的返回值不是给人看的，是**给模型看的**。这决定了它的设计：
 *
 * 1. 错误必须是「可读的、可行动的」。
 *    模型会读取错误信息并据此修正下一次调用。
 *    如果抛出一个 NullPointerException 或 "系统错误"，模型拿到的信息量是零，
 *    它只能原样重试，然后再次失败 —— 这叫「不可自愈」。
 *
 *    反例：模型传了手机号当订单号
 *      throw new RuntimeException("IllegalArgumentException")   ← 模型完全不知道该怎么办
 *    正例：
 *      ToolResult.error("订单号格式错误，应为 12 位纯数字，你传入的是 \"13800138000\"。"
 *                     + "如果只想看最近的订单，可以不传订单号，我会列出你的全部订单")
 *    正例里模型能读懂三件事：错在哪、正确格式是什么、退路是什么。
 *
 * 2. 工具**不抛异常**，异常一律转换成 error 结果返回。
 *    这是 Agent 与固定流程的本质区别：固定流程遇到错误整条链路失败；
 *    Agent 能拿到错误后换一条路走完。前提是错误被「回填」而不是「抛出」。
 *
 * 3. 成功时也带一句 message，让模型知道「这次调用到底拿到了什么」，
 *    而不是让它从 JSON 结构里猜。比如 data 为空列表时，
 *    message 写「你还没有任何订单」，模型就不会误以为调用失败。
 *
 * ============================ 与业务异常的关系 ============================
 * 项目里业务校验用 Asserts.fail(...) 抛 BusinessException。工具层必须把它兜住：
 * 在这里 catch 并转成 error(...)，而不是让异常穿出工具边界。
 * 兜住的理由：一旦异常穿出去，Spring AI 的 MethodToolCallback 会把它包成
 * ToolExecutionException，模型看到的是堆栈摘要而不是人话。
 *
 * Created 2026/10/20.
 */
public class ToolResult<T> {

    /** 是否成功。模型可以据此判断要不要换方案。 */
    private boolean success;

    /** 给模型看的一句话。成功时说明拿到了什么；失败时说明错在哪、怎么改。 */
    private String message;

    /** 结构化数据。已做过瘦身，只保留模型推理需要的字段。 */
    private T data;

    /** 业务错误码，便于排查；对模型而言 message 才是主要信息。 */
    private Integer errorCode;

    public ToolResult() {
    }

    public static <T> ToolResult<T> ok(T data, String message) {
        ToolResult<T> r = new ToolResult<>();
        r.success = true;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> ToolResult<T> error(String message) {
        return error(null, message);
    }

    public static <T> ToolResult<T> error(Integer errorCode, String message) {
        ToolResult<T> r = new ToolResult<>();
        r.success = false;
        r.message = message;
        r.errorCode = errorCode;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }
}
