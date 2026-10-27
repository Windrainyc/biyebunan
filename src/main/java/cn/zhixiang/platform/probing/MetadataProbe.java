package cn.zhixiang.platform.probing;
import java.util.*;
import java.net.*;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;
import cn.zhixiang.platform.shared.BusinessException;
/** 模型目录和域名TLS只读探测；默认关闭、精确主机白名单、不跟随重定向。 */
@Component
public class MetadataProbe {
 private static final java.util.concurrent.ThreadPoolExecutor RESOLVER=new java.util.concurrent.ThreadPoolExecutor(0,2,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.SynchronousQueue<>(),r->{Thread t=new Thread(r,"configuration-probe-dns");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
 private final boolean enabled,localTest;private final Set<String> hosts;private final JsonMapper json;
 /** 生产只允许HTTPS，主机白名单由受保护部署配置指定。
  * @param env ZX_CONFIG_PROBES_ENABLED和ZX_PROBE_ALLOWED_HOSTS
  * @param json 有界响应解析 */
 @Autowired public MetadataProbe(Environment env,JsonMapper json){this(env.getProperty("ZX_CONFIG_PROBES_ENABLED",Boolean.class,false),Set.copyOf(Arrays.asList(env.getProperty("ZX_PROBE_ALLOWED_HOSTS","").split(","))),false,json);}
 /** 仅隔离协议测试允许localhost明文HTTP，不能通过生产环境参数开启。
  * @param enabled 是否启用
  * @param hosts 白名单
  * @param localTest 隔离测试开关
  * @param json 解析器 */
 public MetadataProbe(boolean enabled,Set<String> hosts,boolean localTest,JsonMapper json){this.enabled=enabled;this.hosts=hosts;this.localTest=localTest;this.json=json;}
 /** 验证OpenAI兼容模型目录含目标模型，不发送聊天/生成请求。
  * @param endpoint 完整chat/completions端点
  * @param code 实际模型ID
  * @param secret 当前服务端解密的凭证，不记录
  * @throws BusinessException 禁用、不支持协议、鉴权或模型不存在 */
 public void model(String endpoint,String code,String secret){URI uri=checked(endpoint);String path=uri.getPath();if(path==null||!path.endsWith("/chat/completions"))throw fail("MODEL_PROBE_UNSUPPORTED");String target=endpoint.substring(0,endpoint.length()-"chat/completions".length())+"models";var request=new okhttp3.Request.Builder().url(target).header("Authorization","Bearer "+secret).get().build();try(var owned=new Owned(client(uri));var response=owned.client().newCall(request).execute()){if(response.code()!=200)throw fail(response.code()==401||response.code()==403?"MODEL_CREDENTIAL_REJECTED":"MODEL_METADATA_UNAVAILABLE");var body=response.body();if(body==null||body.contentLength()>1048576)throw fail("MODEL_METADATA_INVALID");byte[] bytes=body.byteStream().readNBytes(1048577);if(bytes.length>1048576)throw fail("MODEL_METADATA_INVALID");var data=json.readTree(bytes).get("data");if(data==null||!data.isArray())throw fail("MODEL_METADATA_INVALID");for(var item:data)if(item.has("id")&&code.equals(item.get("id").asText()))return;throw fail("MODEL_NOT_LISTED");}catch(BusinessException e){throw e;}catch(Exception e){throw fail("MODEL_METADATA_UNAVAILABLE");}}
 /** 验证DNS、有效TLS主机证书和HTTPS响应，不能替代应用业务验收。
  * @param hostname 配置域名
  * @throws BusinessException 网络/TLS失败或HTTP服务器错误 */
 public void domain(String hostname){URI uri=checked("https://"+hostname+"/");try(var owned=new Owned(client(uri));var response=owned.client().newCall(new okhttp3.Request.Builder().url(uri.toString()).head().build()).execute()){if(response.code()>=500)throw fail("DOMAIN_HTTP_UNAVAILABLE");}catch(BusinessException e){throw e;}catch(Exception e){throw fail("DOMAIN_TLS_UNAVAILABLE");}}
 private URI checked(String value){if(!enabled)throw fail("CONFIG_PROBES_DISABLED");try{URI uri=URI.create(value);if(uri.getHost()==null||!hosts.contains(uri.getHost())||uri.getRawQuery()!=null||uri.getFragment()!=null||uri.getUserInfo()!=null||!(uri.getScheme().equals("https")||localTest&&uri.getScheme().equals("http")&&Set.of("localhost","127.0.0.1").contains(uri.getHost())))throw new IllegalArgumentException();return uri;}catch(IllegalArgumentException e){throw fail("PROBE_TARGET_DENIED");}}
 private okhttp3.OkHttpClient client(URI uri) throws Exception {var lookup=RESOLVER.submit(()->List.of(InetAddress.getAllByName(uri.getHost())));java.util.List<InetAddress> addresses;try{addresses=lookup.get(5,java.util.concurrent.TimeUnit.SECONDS);}finally{lookup.cancel(true);}if(!localTest&&addresses.stream().anyMatch(a->restricted(a)))throw fail("PROBE_TARGET_DENIED");return new okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(20)).dns(host->{if(!host.equals(uri.getHost()))throw new UnknownHostException("目标不匹配");return addresses;}).build();}
 // DNS固定为本次解析结果；额外拒绝JDK未覆盖的ULA与运营商共享地址。
 private boolean restricted(InetAddress a){byte[] b=a.getAddress();return a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()||b.length==16&&(b[0]&0xfe)==0xfc||b.length==4&&(b[0]&255)==100&&(b[1]&255)>=64&&(b[1]&255)<=127;}
 private record Owned(okhttp3.OkHttpClient client) implements AutoCloseable {
  @Override public void close(){client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
 }
 private BusinessException fail(String code){return new BusinessException(503,code,"配置探测未通过，请核对受保护部署配置和依赖服务");}
}
