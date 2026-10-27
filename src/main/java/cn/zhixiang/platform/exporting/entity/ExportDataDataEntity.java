package cn.zhixiang.platform.exporting.entity;

import org.apache.ibatis.annotations.*;

import java.util.*;

/**
 * 白名单输出行。
 *
 * @param orderId 需要下载时复核的订单，可空
 * @param cells 有序JSON单元格
 */
public record ExportDataDataEntity(UUID orderId, String cells) {}
