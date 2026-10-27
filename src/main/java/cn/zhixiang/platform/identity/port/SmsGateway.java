package cn.zhixiang.platform.identity.port;

import cn.zhixiang.platform.identity.dto.VerificationDtos;

import java.util.UUID;

/** 短信发送端口；只有供应商明确接受才正常返回，不确定结果不自动重发。 */
public interface SmsGateway {
    /**
     * 执行参数所描述的业务变更，异常由统一错误处理器转换。
     *
     * @throws cn.zhixiang.platform.shared.exception.BusinessException 未配置时明确503，发码前失败
     */
    void requireConfigured();

    /**
     * 执行参数所描述的业务变更，异常由统一错误处理器转换。
     *
     * @param challenge 挑战标识，供应商幂等号
     * @param phone 接收手机号
     * @param code 临时验证码
     * @param purpose 用途
     * @throws cn.zhixiang.platform.shared.exception.BusinessException 发送失败或结果未知
     */
    void send(UUID challenge, String phone, String code, VerificationDtos.Purpose purpose);
}
