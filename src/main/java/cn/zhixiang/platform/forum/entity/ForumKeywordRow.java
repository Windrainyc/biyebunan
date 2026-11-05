package cn.zhixiang.platform.forum.entity;

/**
 * 公开帖子真实标签或板块词频。
 *
 * @param name 词语
 * @param count 近七天关联帖子数量，同名标签与板块合并去重
 */
public record ForumKeywordRow(String name, long count) {}
