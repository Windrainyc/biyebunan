package cn.zhixiang.platform.shared;
import org.springframework.context.annotation.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
/** 最小权限 Redis 健康探针；执行应用所需读写与 PING，无需授予服务器 INFO 管理权限。 */
@Configuration
public class RedisHealthConfiguration {
 /** @param factory Redis 连接工厂 @return 与内置同名的真实读写健康探针 */
 @Bean("redisHealthIndicator")
 public HealthIndicator redisHealthIndicator(RedisConnectionFactory factory) {
  return ()->{
   byte[] key=("zx:health:"+java.util.UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
   byte[] value="probe".getBytes(StandardCharsets.UTF_8);
   try(var c=factory.getConnection()) {
    if(!"PONG".equals(c.ping()))return Health.down().build();
    c.stringCommands().setEx(key,10,value);
    boolean equal=java.util.Arrays.equals(value,c.stringCommands().get(key));
    c.keyCommands().del(key);
    return equal?Health.up().build():Health.down().build();
   } catch(Exception e) {return Health.down().withDetail("stage","redis-read-write").build();}
  };
 }
}
