package cn.zhixiang.platform.identity.port;

import cn.zhixiang.platform.identity.dto.VerificationDtos;
import cn.zhixiang.platform.identity.model.Actor;

/** 认证入口到订单归属证明的业务端口，不依赖订单实现细节。 */
public interface OrderChallengePort {
    /**
     * 发起仅供当前学生绑定指定订单的手机挑战。
     *
     * @param input 手机、订单号及用途
     * @param actor 服务端已认证学生，可空时拒绝
     * @param ip 请求来源，用于限流
     * @return 已被短信适配器接收的挑战
     */
    VerificationDtos.Challenge challenge(
            VerificationDtos.ChallengeRequest input, Actor actor, String ip);
}
