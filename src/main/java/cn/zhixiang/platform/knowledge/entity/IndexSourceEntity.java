package cn.zhixiang.platform.knowledge.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 不可变源。
 *
 * @param title 标题
 * @param type 类型
 * @param markdown 正文
 */
public record IndexSourceEntity(String title, String type, String markdown) {}
