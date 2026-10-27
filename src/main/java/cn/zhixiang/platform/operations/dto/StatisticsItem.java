package cn.zhixiang.platform.operations.dto;

import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/**
 * 单个日指标。
 *
 * @param metric 稳定指标代码，含义见指标字典
 * @param value 十进制字符串，金额保留数据库精度
 * @param unit count/CNY/token
 * @param bucket 北京时间日期YYYY-MM-DD
 */
public record StatisticsItem(String metric, String value, String unit, String bucket) {}
