package cn.zhixiang.platform.storage;
import io.minio.*;
import java.util.*;
import java.time.*;
import java.security.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import cn.zhixiang.platform.shared.BusinessException;
/** 真实MinIO/S3兼容适配器，固定部署端点与桶白名单，关闭重定向；未配置时显式503。 */
@Component
public class S3ObjectStorage implements ObjectStorage {
 private final MinioClient client;private final String endpoint,region,reference;private final Set<String> buckets;
 /** 从受保护环境读取凭证，绝不从数据库任意地址发送密钥。
 * @param env 部署环境；ZX_S3_*为凭证和固定端点 */
 public S3ObjectStorage(Environment env){
  endpoint=env.getProperty("ZX_S3_ENDPOINT","");region=env.getProperty("ZX_S3_REGION","us-east-1");reference=env.getProperty("ZX_S3_CREDENTIAL_REF","local-minio");
  buckets=Set.copyOf(Arrays.asList(env.getProperty("ZX_S3_BUCKETS","zx-project-private,zx-knowledge-private,zx-forum-private,zx-quarantine-private").split(",")));
  String access=env.getProperty("ZX_S3_ACCESS_KEY",""),secret=env.getProperty("ZX_S3_SECRET_KEY","");
  if(endpoint.isBlank()||access.isBlank()||secret.isBlank()){client=null;return;}
  var http=new okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(60)).writeTimeout(Duration.ofSeconds(60)).callTimeout(Duration.ofSeconds(120)).build();
  client=MinioClient.builder().endpoint(endpoint).region(region).credentials(access,secret).httpClient(http,true).build();
 }
 /** 生成签名PUT，校验参数后禁止超长或无摘要授权。
 * @param target 位置
 * @param key 业务生成键
 * @param size 字节数，最多2GiB
 * @param mime 媒体类型
 * @param sha256 内容摘要
 * @return 授权 */
 @Override public Grant upload(Target target,String key,long size,String mime,String sha256){check(target,key);if(size<1||size>2147483648L||sha256==null||!sha256.matches("[a-f0-9]{64}")||mime==null||!mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))throw new BusinessException(422,"UPLOAD_INVALID","上传长度、类型或摘要不合法");
  var headers=Map.of("Content-Type",mime,"Content-Length",Long.toString(size),"x-amz-checksum-sha256",Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256)));
  try{return new Grant(client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Http.Method.PUT).bucket(target.bucket()).object(key).expiry(300).extraHeaders(headers).build()),headers,OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(300));}catch(Exception e){throw failure(e);}
 }
 /** 读取真实元数据。
 * @param target 位置
 * @param key 对象键
 * @return 元数据 */
 @Override public Metadata stat(Target target,String key){check(target,key);try{var s=client.statObject(StatObjectArgs.builder().bucket(target.bucket()).object(key).build());return new Metadata(s.size(),s.etag(),s.versionId(),s.contentType());}catch(Exception e){throw failure(e);}}
 /** 固定源版本/ETag后流式计算摘要，限制最多读取声明大小加一字节。
 * @param target 位置
 * @param key 对象键
 * @param expected 已读取元数据
 * @return 真实摘要 */
 @Override public String sha256(Target target,String key,Metadata expected){check(target,key);if(expected.size()<1||expected.size()>2147483648L)throw new BusinessException(422,"ASSET_SIZE_INVALID","对象大小超出限制");
  try(var in=client.getObject(GetObjectArgs.builder().bucket(target.bucket()).object(key).versionId(expected.versionId()).matchETag(expected.etag()).build())){
   var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long total=0;int n;
   while((n=in.read(buffer,0,(int)Math.min(buffer.length,expected.size()-total+1)))!=-1){total+=n;if(total>expected.size())throw new BusinessException(422,"ASSET_SIZE_MISMATCH","对象大小发生变化");digest.update(buffer,0,n);}
   if(total!=expected.size())throw new BusinessException(422,"ASSET_SIZE_MISMATCH","对象读取长度不一致");return HexFormat.of().formatHex(digest.digest());
  }catch(Exception e){throw failure(e);}
 }
 /** 将固定源复制到新键，调用方负责保证新键唯一且从未签发PUT。
 * @param target 位置
 * @param sourceKey 上传键
 * @param expected 源元数据
 * @param finalKey 新私有键
 * @return 私有对象元数据 */
 @Override public Metadata promote(Target target,String sourceKey,Metadata expected,String finalKey){check(target,sourceKey);check(target,finalKey);if(sourceKey.equals(finalKey))throw new BusinessException(422,"ASSET_KEY_INVALID","私有键不得等于上传键");try{client.copyObject(CopyObjectArgs.builder().bucket(target.bucket()).object(finalKey).source(SourceObject.builder().bucket(target.bucket()).object(sourceKey).versionId(expected.versionId()).matchETag(expected.etag()).build()).build());return stat(target,finalKey);}catch(Exception e){throw failure(e);}}
 /** 生成短时私有下载链接，强制attachment防止内联脚本执行。
 * @param target 位置
 * @param key 对象键
 * @param versionId 固定版本
 * @return 下载授权 */
 @Override public Grant download(Target target,String key,String versionId){check(target,key);try{return new Grant(client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Http.Method.GET).bucket(target.bucket()).object(key).versionId(versionId).expiry(60).extraQueryParams(Map.of("response-content-disposition","attachment","response-content-type","application/octet-stream")).build()),Map.of(),OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60));}catch(Exception e){throw failure(e);}}
 /** 删除指定对象，不删除桶或同前缀的其他文件。
 * @param target 位置
 * @param key 对象键
 * @param versionId 可选版本
 */
 @Override public void remove(Target target,String key,String versionId){check(target,key);try{client.removeObject(RemoveObjectArgs.builder().bucket(target.bucket()).object(key).versionId(versionId).build());}catch(Exception e){throw failure(e);}}
 private void check(Target t,String key){if(client==null||!"S3_COMPATIBLE".equals(t.provider()))throw new BusinessException(503,"STORAGE_UNAVAILABLE","对应对象存储适配器尚未配置");if(!endpoint.equals(t.endpoint())||!region.equals(t.region())||!reference.equals(t.credentialRef())||!buckets.contains(t.bucket()))throw new BusinessException(422,"STORAGE_TARGET_DENIED","存储位置不在部署允许范围");if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9/_-]{0,511}")||key.contains("//"))throw new BusinessException(422,"ASSET_KEY_INVALID","对象键须由服务端生成");}
 private BusinessException failure(Exception e){if(e instanceof BusinessException b)return b;if(e instanceof io.minio.errors.ErrorResponseException s&&java.util.Set.of("NoSuchKey","NoSuchObject","NoSuchVersion").contains(s.errorResponse().code()))return new BusinessException(404,"ASSET_OBJECT_MISSING","存储对象不存在");return new BusinessException(503,"STORAGE_UNAVAILABLE","对象存储操作失败，请稍后重试");}
 /** 关闭专用网络连接池。
 */
 @jakarta.annotation.PreDestroy public void close(){if(client!=null)try{client.close();}catch(Exception ignored){/* 关闭失败不能泄漏包含签名的异常；进程退出将释放连接。 */}}
}
