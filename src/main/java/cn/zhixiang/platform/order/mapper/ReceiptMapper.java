package cn.zhixiang.platform.order.mapper;

import cn.zhixiang.platform.order.dto.ReceiptDtos;

import org.apache.ibatis.annotations.*;

import java.util.UUID;

/** 收款快照的持久化端口；唯一约束是跨节点去重的最终保障。 */
@Mapper
public interface ReceiptMapper {
    /**
     * 写入不可覆盖的收款记录。
     *
     * @param orderId 已加锁订单
     * @param w 核验材料
     * @param actor 核验管理员
     */
    @Insert(
            "INSERT INTO"
                + " order_receipt(order_id,reference,received_at,amount,currency,proof_asset_id,reason,confirmed_by,channel)"
                + " VALUES(#{orderId},#{w.reference},#{w.receivedAt},CAST(#{w.amount} AS"
                + " numeric),'CNY',#{w.proofAssetId},#{w.reason},#{actor},#{w.channel})")
    void insert(UUID orderId, @Param("w") ReceiptDtos.Write w, UUID actor);

    /**
     * 同事务更新订单收款标记和并发版本。
     *
     * @param id 订单
     * @param version 预期版本
     * @param actor 管理员
     * @return 更新数量
     */
    @Update(
            "UPDATE software_order SET"
                + " receipt_status='CONFIRMED',version=version+1,updated_at=now(),updated_by=#{actor}"
                + " WHERE id=#{id} AND version=#{version} AND receipt_status='UNCONFIRMED'")
    int confirm(UUID id, long version, UUID actor);
}
