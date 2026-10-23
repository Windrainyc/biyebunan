package cn.zhixiang.platform.shared;
import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;
/** PostgreSQL 原生 UUID 绑定，避免把 UUID 当成 VARCHAR 导致运算符类型不匹配。 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    /**
 * @param ps 语句
 * @param i 参数位置
 * @param value UUID
 * @param type JDBC 类型
 * @throws SQLException 数据库失败 */
    @Override public void setNonNullParameter(PreparedStatement ps,int i,UUID value,JdbcType type) throws SQLException { ps.setObject(i,value); }
    /**
 * @param rs 结果集
 * @param name 列名
 * @return UUID 或 null
 * @throws SQLException 数据库失败 */
    @Override public UUID getNullableResult(ResultSet rs,String name) throws SQLException { return rs.getObject(name,UUID.class); }
    /**
 * @param rs 结果集
 * @param index 列序号
 * @return UUID 或 null
 * @throws SQLException 数据库失败 */
    @Override public UUID getNullableResult(ResultSet rs,int index) throws SQLException { return rs.getObject(index,UUID.class); }
    /**
 * @param cs 存储过程
 * @param index 列序号
 * @return UUID 或 null
 * @throws SQLException 数据库失败 */
    @Override public UUID getNullableResult(CallableStatement cs,int index) throws SQLException { return cs.getObject(index,UUID.class); }
}
