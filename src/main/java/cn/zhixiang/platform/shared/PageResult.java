package cn.zhixiang.platform.shared;
import java.util.List;
/** 列表响应；总数必须在同一权限和过滤范围内计算。
 * @param items 当前页数据 @param page 页码 @param size 每页数量 @param total 过滤后总数
 * @param <T> 对外响应 DTO 类型
 */
public record PageResult<T>(List<T> items,int page,int size,long total) {}
