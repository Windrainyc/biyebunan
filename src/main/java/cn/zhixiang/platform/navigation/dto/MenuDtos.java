package cn.zhixiang.platform.navigation.dto;

import cn.zhixiang.platform.shared.dto.PageQuery;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 三端菜单请求和响应；外链/脚本地址不允许配置为页面路径。 */
public final class MenuDtos {
    private MenuDtos() {}

    /**
     * 当前平台的导航快照；空的可见列表不能误判为未配置。
     *
     * @param configured 平台存在任意菜单配置（包括停用项），为true时禁止回退默认导航
     * @param menus 当前账户可见菜单页，完整祖先链经过服务端权限过滤
     */
    public record Navigation(
            boolean configured, cn.zhixiang.platform.shared.dto.PageResult<View> menus) {}

    /**
     * 已验证业务写入请求，字段不含服务端生成的标识或版本。
     *
     * @param name 中文显示名
     * @param code 平台内唯一代码
     * @param platform 服务端归属平台
     * @param parentId 父菜单，根为null
     * @param path 站内绝对路径，不允许协议、转义或路径穿越
     * @param permissionCode 所属平台已登记权限
     * @param sortOrder 同级顺序，非负
     * @param status 启停
     */
    public record Write(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String code,
            @NotNull @Pattern(regexp = "admin|student|partner") String platform,
            UUID parentId,
            @NotNull @Size(max = 500) @Pattern(regexp = "/(?!/)[A-Za-z0-9/_-]*") String path,
            @NotBlank @Size(max = 200) String permissionCode,
            @NotNull @Min(0) Integer sortOrder,
            @NotNull @Pattern(regexp = "ENABLED|DISABLED") String status) {}

    /**
     * 只读响应数据，版本及审计字段由服务端生成。
     *
     * @param id 菜单标识
     * @param version CAS
     * @param createdAt 创建
     * @param updatedAt 更新
     * @param name 名称
     * @param code 代码
     * @param platform 平台
     * @param parentId 父菜单
     * @param path 站内路径
     * @param permissionCode 可见权限，接口仍独立授权
     * @param sortOrder 顺序
     * @param status 启停
     */
    public record View(
            UUID id,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            String name,
            String code,
            String platform,
            UUID parentId,
            String path,
            String permissionCode,
            int sortOrder,
            String status) {}

    /**
     * 模块数据记录，字段含义和取值约束见参数说明。
     *
     * @param page 页码
     * @param size 每页大小
     * @param q 名称/代码检索
     * @param status 启停过滤
     * @param sort 菜单专有白名单，默认sortOrder:asc
     */
    public record Query(int page, int size, String q, String status, String sort) {
        /**
         * 校验并归一化分页输入，拒绝不支持的排序或过大的偏移。
         *
         * @param page 页码
         * @param size 每页数
         * @param q 字面搜索
         * @param status 状态过滤
         * @param sort 白名单排序
         */
        public Query {
            sort = sort == null ? "sortOrder:asc" : sort;
            var base =
                    new PageQuery(
                            page,
                            size,
                            q,
                            status,
                            sort.equals("sortOrder:asc") ? "createdAt:asc" : sort);
            q = base.q();
        }

        /**
         * 返回安全SQL排序，稳定附加UUID。
         *
         * @return 安全SQL排序，稳定附加UUID
         */
        public String orderBy() {
            return sort.equals("sortOrder:asc")
                    ? "sort_order ASC,id ASC"
                    : new PageQuery(page, size, q, status, sort).orderBy();
        }

        /**
         * 返回安全偏移。
         *
         * @return 安全偏移
         */
        public int offset() {
            return (page - 1) * size;
        }

        /**
         * 返回LIKE字面查询。
         *
         * @return LIKE字面查询
         */
        public String pattern() {
            return new PageQuery(page, size, q, status, null).pattern();
        }
    }
}
