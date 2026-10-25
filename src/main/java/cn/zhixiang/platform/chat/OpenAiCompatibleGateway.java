package cn.zhixiang.platform.chat;
import java.util.*;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;
/** 有界OpenAI兼容文本流适配；默认关闭，禁止自动重试付费请求与重定向泄露密钥。 */
@Component
public class OpenAiCompatibleGateway implements ModelGateway {
 private final boolean enabled;private final Set<String> hosts;private final boolean localTest;private final JsonMapper json;private final OkHttpClient http;
 /** 从部署环境读取明确开关及允许的主机，不从客户端参数放宽限制。
 * @param env ZX_MODEL_CALLS_ENABLED默认false，ZX_MODEL_ALLOWED_HOSTS逗号分隔
 * @param json JSON编解码 */
 @org.springframework.beans.factory.annotation.Autowired
 public OpenAiCompatibleGateway(Environment env,JsonMapper json){this(env.getProperty("ZX_MODEL_CALLS_ENABLED",Boolean.class,false),new HashSet<>(Arrays.asList(env.getProperty("ZX_MODEL_ALLOWED_HOSTS","").split(","))),false,json);}
 // 包内构造仅供隔离HTTP协议测试；生产Bean始终要求HTTPS。
 OpenAiCompatibleGateway(boolean enabled,Set<String> hosts,boolean localTest,JsonMapper json){this.enabled=enabled;this.hosts=Set.copyOf(hosts);this.localTest=localTest;this.json=json;http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(5,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).build();}
 /** 执行完整流，要求最终用量与DONE；缺失用量保留未知状态。
 * @param request 固定上下文
 * @param delta 增量持久化回调
 * @return 供应商用量 */
 @Override public Usage stream(ModelGateway.Request request,Consumer<String> delta){
  if(!enabled)throw new Failure("MODEL_PROVIDER_DISABLED",true);URI uri;
  try{uri=URI.create(request.endpoint());if(uri.getHost()==null||!hosts.contains(uri.getHost())||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getFragment()!=null||!("https".equals(uri.getScheme())||localTest&&"http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost").contains(uri.getHost())))throw new IllegalArgumentException();if(request.secret()==null||request.secret().isBlank()||request.maxOutputTokens()<1||request.maxOutputTokens()>8192)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw new Failure("MODEL_CONFIGURATION_INVALID",true);}
  if(request.runId()==null||request.model()==null||request.model().isBlank()||request.messages().isEmpty()||request.messages().size()>128)throw new Failure("MODEL_REQUEST_INVALID",true);
  long inputChars=0;for(var turn:request.messages()){if(turn.role()==null||!Set.of("system","user","assistant").contains(turn.role())||turn.content()==null||turn.content().isBlank())throw new Failure("MODEL_REQUEST_INVALID",true);inputChars+=turn.content().length();if(inputChars>1048576)throw new Failure("MODEL_CONTEXT_LIMIT",true);}
  okhttp3.Request call;
  try{var payload=Map.of("model",request.model(),"messages",request.messages(),"max_tokens",request.maxOutputTokens(),"stream",true,"stream_options",Map.of("include_usage",true));call=new okhttp3.Request.Builder().url(uri.toString()).header("Authorization","Bearer "+request.secret()).header("X-Client-Request-Id",request.runId().toString()).post(RequestBody.create(json.writeValueAsBytes(payload),MediaType.get("application/json"))).build();}catch(Exception e){throw new Failure("MODEL_REQUEST_INVALID",true);}
  try(var response=http.newCall(call).execute()){
   if(!response.isSuccessful())throw new Failure("MODEL_HTTP_"+response.code(),Set.of(400,401,403,404,422,429).contains(response.code()));
   if(response.body()==null||!Objects.toString(response.header("Content-Type"),"").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))throw new Failure("MODEL_PROTOCOL_INVALID",false);
   var source=response.body().source();var event=new StringBuilder();long count=0;Usage usage=null;boolean done=false;String providerId=response.header("x-request-id");
   while(!source.exhausted()){
    String line=source.readUtf8LineStrict(65536);
    if(line.isEmpty()){
     if(event.isEmpty())continue;String data=event.toString();event.setLength(0);if(data.equals("[DONE]")){done=true;break;}
     var value=json.readTree(data);if(value.hasNonNull("error"))throw new Failure("MODEL_STREAM_ERROR",false);
     var choices=value.get("choices");if(choices!=null&&choices.isArray())for(var choice:choices){var d=choice.get("delta");if(d!=null&&d.hasNonNull("content")){String text=d.get("content").asText();count+=text.length();if(count>262144)throw new Failure("MODEL_OUTPUT_LIMIT",false);if(!text.isEmpty())delta.accept(text);}}
     var u=value.get("usage");if(u!=null&&!u.isNull()){
      if(usage!=null)throw new Failure("MODEL_USAGE_DUPLICATE",false);long input=integer(u.get("prompt_tokens")),output=integer(u.get("completion_tokens"));var details=u.get("prompt_tokens_details");long cached=details==null||details.isNull()||!details.hasNonNull("cached_tokens")?0:integer(details.get("cached_tokens"));if(cached>input)throw new Failure("MODEL_USAGE_INVALID",false);usage=new Usage(input,output,cached,providerId);
     }
    }else if(line.startsWith("data:")){if(!event.isEmpty())event.append('\n');event.append(line.substring(5).stripLeading());if(event.length()>65536)throw new Failure("MODEL_EVENT_LIMIT",false);}
   }
   if(!done||usage==null)throw new Failure("MODEL_USAGE_UNKNOWN",false);return usage;
  }catch(Failure e){throw e;}catch(Exception e){throw new Failure("MODEL_OUTCOME_UNKNOWN",false);}
 }
 private long integer(tools.jackson.databind.JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong()||node.asLong()<0)throw new Failure("MODEL_USAGE_INVALID",false);return node.asLong();}
}
