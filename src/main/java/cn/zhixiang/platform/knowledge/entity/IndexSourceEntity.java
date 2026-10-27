package cn.zhixiang.platform.knowledge.entity;

import java.util.*;

/**
 * 不可变源。
 *
 * @param title 标题
 * @param type 类型
 * @param markdown 正文
 * @param assetId 不可变快照引用资产
 */
public record IndexSourceEntity(String title, String type, String markdown, UUID assetId) {}
