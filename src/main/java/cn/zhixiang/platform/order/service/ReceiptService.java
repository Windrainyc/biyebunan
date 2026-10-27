package cn.zhixiang.platform.order.service;

import cn.zhixiang.platform.asset.mapper.AssetMapper;
import cn.zhixiang.platform.identity.model.Actor;
import cn.zhixiang.platform.order.dto.OrderDtos;
import cn.zhixiang.platform.order.dto.ReceiptDtos;
import cn.zhixiang.platform.order.mapper.OrderMapper;
import cn.zhixiang.platform.order.mapper.ReceiptMapper;
import cn.zhixiang.platform.shared.exception.BusinessException;
import cn.zhixiang.platform.shared.mapper.WriteMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/** 人工全额收款用例；事务内按订单→凭证资产加锁，不能用Redis余额或客户端标记替代。 */
@Service
public class ReceiptService {
    private final OrderMapper orders;
    private final AssetMapper assets;
    private final ReceiptMapper receipts;
    private final OrderService orderService;
    private final WriteMapper writes;

    /**
     * 创建用例。
     *
     * @param orders 订单持久层
     * @param assets 资产授权与行锁
     * @param receipts 收款记录
     * @param orderService 订单响应
     * @param writes 审计及事务事件
     */
    public ReceiptService(
            OrderMapper orders,
            AssetMapper assets,
            ReceiptMapper receipts,
            OrderService orderService,
            WriteMapper writes) {
        this.orders = orders;
        this.assets = assets;
        this.receipts = receipts;
        this.orderService = orderService;
        this.writes = writes;
    }

    /**
     * 确认全额到账；幂等重放由外层协调器完成，不允许第二条流水覆盖既有收款。
     *
     * @param id 订单
     * @param version 期望版本
     * @param w 真实核验材料
     * @param actor 具备财务权限的管理员
     * @return 更新后的订单
     * @throws BusinessException 版本、状态、金额或凭证归属不符合约束
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderDtos.View confirm(UUID id, long version, ReceiptDtos.Write w, Actor actor) {
        if (!"admin".equals(actor.platform()))
            throw new BusinessException(403, "FORBIDDEN", "仅管理员可核验收款");
        var order = orders.lock(id, actor);
        if (order == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "订单不存在");
        if (order.version() != version)
            throw new BusinessException(412, "VERSION_CONFLICT", "订单已更新");
        if (!order.status().equals("CONFIRMED") || !order.receiptStatus().equals("UNCONFIRMED"))
            throw new BusinessException(422, "ORDER_RECEIPT_STATE", "仅已确认且未收款的订单可核验");
        var amount = new BigDecimal(w.amount());
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal(order.amount())) != 0)
            throw new BusinessException(422, "RECEIPT_AMOUNT_MISMATCH", "本接口仅支持订单全额确认");
        var asset = assets.lock(w.proofAssetId());
        if (asset == null
                || asset.deletedAt() != null
                || !id.equals(asset.receiptOrderId())
                || !asset.state().equals("READY"))
            throw new BusinessException(422, "RECEIPT_PROOF_INVALID", "凭证必须属于当前订单且通过扫描");
        receipts.insert(
                id,
                new ReceiptDtos.Write(
                        w.reference().trim(),
                        w.receivedAt(),
                        w.amount(),
                        w.proofAssetId(),
                        w.reason().trim()),
                actor.id());
        if (receipts.confirm(id, version, actor.id()) != 1)
            throw new BusinessException(412, "VERSION_CONFLICT", "订单已更新");
        writes.audit(UUID.randomUUID(), actor.id(), "RECEIPT_CONFIRMED", "order", id);
        writes.event(UUID.randomUUID(), id, "order.RECEIPT_CONFIRMED", "{\"id\":\"" + id + "\"}");
        return orderService.get(id, actor);
    }
}
