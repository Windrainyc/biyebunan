package cn.zhixiang.platform.storage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import cn.zhixiang.platform.shared.BusinessException;
/** ClamAV INSTREAM适配器；地址只来自受控部署配置，未配置不放行。 */
@Component
public class ClamAvScanner implements ContentScanner {
 // 全流程截止时间会主动关闭socket，防止对端不读取导致写操作永久占用worker。
 private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES;
 static {DEADLINES=new java.util.concurrent.ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon(true).name("zx-scan-deadline").factory());DEADLINES.setRemoveOnCancelPolicy(true);}
 private final String host;private final int port;
 /** 从部署环境创建扫描器，默认关闭。
 * @param env ZX_CLAMAV_HOST和ZX_CLAMAV_PORT */
 public ClamAvScanner(Environment env){host=env.getProperty("ZX_CLAMAV_HOST","");port=env.getProperty("ZX_CLAMAV_PORT",Integer.class,3310);}
 /** 将文件按ClamAV协议分块发送，严格识别终止结果。
 * @param input 文件流
 * @param size 字节数
 * @return 是否确认安全 */
 @Override public boolean clean(InputStream input,long size){
  if(host.isBlank())throw new BusinessException(503,"SCANNER_UNAVAILABLE","恶意内容扫描服务未配置");
  try(var socket=new Socket()){
   var deadline=DEADLINES.schedule(()->{try{socket.close();}catch(IOException ignored){}},120,java.util.concurrent.TimeUnit.SECONDS);
   try {
   socket.connect(new InetSocketAddress(host,port),5000);socket.setSoTimeout(30000);
   var out=new DataOutputStream(socket.getOutputStream());out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));byte[] buffer=new byte[32768];long count=0;int n;
   while((n=input.read(buffer))!=-1){count+=n;if(count>size)throw new IOException("size mismatch");out.writeInt(n);out.write(buffer,0,n);}
   if(count!=size)throw new IOException("size mismatch");out.writeInt(0);out.flush();
   var response=new ByteArrayOutputStream();int value;while((value=socket.getInputStream().read())>0){if(response.size()>=4096)throw new IOException("oversize result");response.write(value);}
   if(value!=0)throw new IOException("incomplete result");String result=response.toString(StandardCharsets.US_ASCII);
   if(result.equals("stream: OK"))return true;if(result.startsWith("stream: ")&&result.endsWith(" FOUND"))return false;throw new IOException("scanner error");
   }finally{deadline.cancel(false);}
  }catch(IOException e){throw new BusinessException(503,"SCANNER_UNAVAILABLE","文件扫描未完成，请稍后重试");}
 }
}
