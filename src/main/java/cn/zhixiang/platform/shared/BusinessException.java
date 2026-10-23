package cn.zhixiang.platform.shared;

/** 可向客户端公开的业务错误；detail 不得包含 SQL、凭证或供应商原始响应。 */
public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;
    /**
 * @param status HTTP 状态
 * @param code 稳定业务错误码
 * @param detail 安全的用户提示 */
    public BusinessException(int status, String code, String detail) {
        super(detail); this.status = status; this.code = code;
    }
    /**
 * @return 真实 HTTP 状态 */
    public int status() { return status; }
    /**
 * @return 供客户端判断的稳定错误码 */
    public String code() { return code; }
}
