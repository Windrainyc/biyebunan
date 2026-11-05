package cn.zhixiang.platform.forum.dto;

import java.util.List;

/** 社区聚合响应，所有数据排除隐藏、删除、待审和停用板块。 */
public final class ForumCommunityDtos {
    private ForumCommunityDtos() {}

    /**
     * 社区摘要。
     *
     * @param activeUsers 近七天公开贡献最多的十二名成员
     * @param hotPosts 近七天有公开回复或创建的前三条热门帖子
     * @param keywords 近七天公开帖子标签和板块词频前十二名
     */
    public record Summary(
            List<ForumActiveResponse> activeUsers,
            List<ForumFeedResponse> hotPosts,
            List<ForumKeywordResponse> keywords) {}
}
