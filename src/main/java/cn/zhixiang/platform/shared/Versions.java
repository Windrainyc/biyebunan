package cn.zhixiang.platform.shared;
/** 强 ETag 解析；禁止通配符更新以绕过并发覆盖保护。 */
public final class Versions {
    private Versions() {}
    /** @param value If-Match 原始值 @return 期望版本 @throws BusinessException 无效或溢出的版本 */
    public static long parse(String value) {
        if(value==null || !value.matches("\"[1-9][0-9]{0,18}\"")) throw new BusinessException(400,"VALIDATION_ERROR","If-Match 必须为双引号包围的正整数版本");
        try { return Long.parseLong(value.substring(1,value.length()-1)); }
        catch(NumberFormatException e) { throw new BusinessException(400,"VALIDATION_ERROR","版本超出范围"); }
    }
    /** @param version 正整数资源版本 @return 强 ETag */
    public static String etag(long version) { return "\""+version+"\""; }
}
