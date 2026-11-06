package cn.zhixiang.platform.configuration.dto;

/**
 * 迁移进度，未完成引用禁止停止旧存储。
 *
 * @param queued 排队
 * @param running 执行中
 * @param done 已校验切换
 * @param failed 失败需核查
 * @param remainingAssets 旧存储资产数
 * @param remainingExports 旧存储导出数
 * @param canStopOldStorage 是否已具备自动停止条件，本版本始终需要人工核对备份和GC
 */
public record StorageMigrationView(
        long queued,
        long running,
        long done,
        long failed,
        long remainingAssets,
        long remainingExports,
        boolean canStopOldStorage) {}
