package cn.zhixiang.platform.operations.dto;

import jakarta.validation.constraints.*;

import java.time.*;
import java.util.*;

/** 运营活动输入和安全响应，不接收身份、IP或正文。 */
public final class ActivityDtos {
    private ActivityDtos() {}

    /**
     * 浏览器可见活跃片段，时间为估算，不作计费依据。
     *
     * @param id 幂等事件标识
     * @param path 无查询参数的页面路径
     * @param contentId 正在阅读的知识标识，可空
     * @param seconds 可见且近期有交互的秒数，最多30
     */
    public record Write(
            @NotNull UUID id,
            @NotBlank @Pattern(regexp = "/(admin|student|partner)(/[a-z-]+){0,2}") @Size(max = 160)
                    String path,
            UUID contentId,
            @Min(0) @Max(30) int seconds) {}

    /**
     * 管理员可见的访问记录。
     *
     * @param id 事件
     * @param actorId 用户
     * @param displayName 用户名称
     * @param platform 平台
     * @param kind LOGIN或VIEW
     * @param path 页面
     * @param contentId 知识标识
     * @param contentTitle 知识名称
     * @param seconds 估算活跃秒数
     * @param ip 服务端观测来源地址，代理未配置时为代理地址
     * @param location 地点，未配置定位来源时未知
     * @param createdAt 服务端时间
     */
    public record View(
            UUID id,
            UUID actorId,
            String displayName,
            String platform,
            String kind,
            String path,
            UUID contentId,
            String contentTitle,
            int seconds,
            String ip,
            String location,
            OffsetDateTime createdAt) {}

    /**
     * 自然日汇总。
     *
     * @param day 北京日期
     * @param loginUsers 登录人数（当日去重）
     * @param logins 登录次数
     * @param views 浏览记录数
     * @param activeSeconds 可见活跃秒数
     * @param learningSeconds 知识阅读秒数
     */
    public record Daily(
            java.time.LocalDate day,
            long loginUsers,
            long logins,
            long views,
            long activeSeconds,
            long learningSeconds) {}
}
