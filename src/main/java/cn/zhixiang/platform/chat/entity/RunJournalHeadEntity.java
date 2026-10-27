package cn.zhixiang.platform.chat.entity;

import java.util.*;

/**
 * 运行流头部。
 *
 * @param id 运行ID
 * @param state 业务状态
 * @param lastSequence 已提交最高序号
 */
public record RunJournalHeadEntity(UUID id, String state, long lastSequence) {}
