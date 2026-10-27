package cn.zhixiang.platform.shared.typehandler;

import org.apache.ibatis.type.*;

import java.sql.*;
import java.util.UUID;

/** PostgreSQL 原生 UUID 绑定，避免把 UUID 当成 VARCHAR 导致运算符类型不匹配。 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    /** 创建PostgreSQL原生UUID类型处理器。 */
    public UuidTypeHandler() {}

    /**
     * 执行参数所描述的业务变更，异常由统一错误处理器转换。
     *
     * @param ps 语句
     * @param i 参数位置
     * @param value UUID
     * @param type JDBC 类型
     * @throws SQLException 数据库失败
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID value, JdbcType type)
            throws SQLException {
        ps.setObject(i, value);
    }

    /**
     * 返回UUID 或 null。
     *
     * @param rs 结果集
     * @param name 列名
     * @return UUID 或 null
     * @throws SQLException 数据库失败
     */
    @Override
    public UUID getNullableResult(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    /**
     * 返回UUID 或 null。
     *
     * @param rs 结果集
     * @param index 列序号
     * @return UUID 或 null
     * @throws SQLException 数据库失败
     */
    @Override
    public UUID getNullableResult(ResultSet rs, int index) throws SQLException {
        return rs.getObject(index, UUID.class);
    }

    /**
     * 返回UUID 或 null。
     *
     * @param cs 存储过程
     * @param index 列序号
     * @return UUID 或 null
     * @throws SQLException 数据库失败
     */
    @Override
    public UUID getNullableResult(CallableStatement cs, int index) throws SQLException {
        return cs.getObject(index, UUID.class);
    }
}
