package cn.zhixiang.platform.shared;

/** 已归一化分页条件；SQL 排序列只能从封闭白名单取得。
 * @param page 从 1 开始 @param size 每页 1 至 100 @param q 名称或代码搜索
 * @param status 启停过滤，可空 @param sort 白名单排序
 */
public record PageQuery(int page,int size,String q,String status,String sort) {
    public PageQuery {
        q = q == null ? "" : q.trim();
        sort = sort == null ? "createdAt:desc" : sort;
        if(page<1 || size<1 || size>100 || q.length()>100 || (long)(page-1)*size>Integer.MAX_VALUE)
            throw new BusinessException(400,"INVALID_FILTER","分页或搜索条件超出范围");
        if(status!=null && !java.util.Set.of("ENABLED","DISABLED").contains(status))
            throw new BusinessException(400,"INVALID_FILTER","启停状态不合法");
        if(!java.util.Set.of("createdAt:desc","createdAt:asc","updatedAt:desc").contains(sort))
            throw new BusinessException(400,"INVALID_FILTER","排序条件不受支持");
    }
    /** @return 不包含用户原始文本的 SQL 排序表达式 */
    public String orderBy() { return switch(sort) {case "createdAt:asc"->"created_at ASC,id ASC";case "updatedAt:desc"->"updated_at DESC,id DESC";default->"created_at DESC,id DESC";}; }
    /** @return 已验证不会溢出的偏移量 */
    public int offset() { return (page-1)*size; }
    /** @return 按字面匹配的 LIKE 参数，转义用户的通配符 */
    public String pattern() { return "%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"; }
}
