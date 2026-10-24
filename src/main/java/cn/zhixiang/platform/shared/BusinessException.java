package cn.zhixiang.platform.shared;

/** 可向客户端公开的业务错误；detail 不得包含 SQL、凭证或供应商原始响应。 */
public class BusinessException extends RuntimeException {
    /** 对外HTTP状态码。 */
    private final int status;
    /** 稳定业务错误码。 */
    private final String code;
    /** 创建只包含安全对外信息的业务异常。
 * @param status HTTP 状态
 * @param code 稳定业务错误码
 * @param detail 安全的用户提示 */
    public BusinessException(int status, String code, String detail) {
        super(detail); this.status = status; this.code = code;
    }
    /** 返回真实 HTTP 状态。
 * @return 真实 HTTP 状态 */
    public int status() { return status; }
    /** 返回供客户端判断的稳定错误码。
 * @return 供客户端判断的稳定错误码 */
    public String code() { return code; }
}
