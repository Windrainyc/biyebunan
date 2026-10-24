package cn.zhixiang.platform.storage;
import java.io.InputStream;
/** 恶意内容扫描端口；无扫描服务时必须失败关闭，不能将文件放行为READY。 */
public interface ContentScanner {
 /** 扫描固定版本的完整文件流；调用者负责关闭输入流。
 * @param input 文件流
 * @param size 已核实字节数
 * @return true为扫描服务确认安全，false为检测到威胁；不可用时抛业务错误 */
 boolean clean(InputStream input,long size);
}
