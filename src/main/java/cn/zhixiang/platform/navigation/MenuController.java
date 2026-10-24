package cn.zhixiang.platform.navigation;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 管理端菜单接口，权限在服务端执行；控制器不直接访问数据库。 */
@RestController
@RequestMapping("/api/v1/admin/menus")

public class MenuController {
 private final MenuService service;private final IdempotentWrites writes;
 /** 初始化模块组件，所需端口和配置由构造器注入。
 * @param service 用例服务
 * @param writes 幂等事务协调器 */
 public MenuController(MenuService service,IdempotentWrites writes) {this.service=service;this.writes=writes;}
 /** 返回分页。
 * @param page 页码
 * @param size 页大小
 * @param q 搜索
 * @param status 启停过滤
 * @param platform 平台筛选，可空
 * @param sort 白名单排序
 * @return 分页 */
 @PreAuthorize("hasAuthority('menus:read')")
 @GetMapping
 public PageResult<MenuDtos.View> list(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@RequestParam(required=false) String platform) {return service.list(new MenuDtos.Query(page,size,q,status,sort),platform);}
 /** 返回资源和 ETag。
 * @param id 标识
 * @return 资源和 ETag */
 @PreAuthorize("hasAuthority('menus:read')")
 @GetMapping("/{id}")
 public ResponseEntity<MenuDtos.View> get(@PathVariable UUID id) {var v=service.get(id);return ResponseEntity.ok().eTag(Versions.etag(v.version())).body(v);}
 /** 返回201 与资源。
 * @param w 写入 DTO
 * @param key 幂等键
 * @param actor 权威身份
 * @return 201 与资源 */
 @PreAuthorize("hasAuthority('menus:write')")
 @PostMapping
 public ResponseEntity<String> create(@Valid @RequestBody MenuDtos.Write w,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {return writes.execute(actor.id(),"POST","/api/v1/admin/menus",key,w,()->{var v=service.create(w,actor.id());return ResponseEntity.status(201).eTag(Versions.etag(v.version())).body(v);});}
 /** 返回新版本。
 * @param id 标识
 * @param w 新值
 * @param match 期望版本
 * @param key 幂等键
 * @param actor 权威身份
 * @return 新版本 */
 @PreAuthorize("hasAuthority('menus:write')")
 @PutMapping("/{id}")
 public ResponseEntity<String> update(@PathVariable UUID id,@Valid @RequestBody MenuDtos.Write w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {long v=Versions.parse(match);return writes.execute(actor.id(),"PUT","/api/v1/admin/menus/"+id,key,List.of(v,w),()->{var result=service.update(id,v,w,actor.id());return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
 /** 返回启停结果。
 * @param id 标识
 * @param w 状态
 * @param match 期望版本
 * @param key 幂等键
 * @param actor 权威身份
 * @return 启停结果 */
 @PreAuthorize("hasAuthority('menus:write')")
 @PostMapping("/{id}/status")
 public ResponseEntity<String> status(@PathVariable UUID id,@Valid @RequestBody cn.zhixiang.platform.identity.RoleDtos.State w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/menus/"+id+"/status",key,List.of(v,w),()->{var result=service.status(id,v,w.status(),actor.id());return ResponseEntity.ok().eTag(Versions.etag(result.version())).body(result);});}
 /** 返回删除成功 204。
 * @param id 标识
 * @param match 期望版本
 * @param key 幂等键
 * @param actor 权威身份
 * @return 删除成功 204 */
 @PreAuthorize("hasAuthority('menus:delete')")
 @DeleteMapping("/{id}")
 public ResponseEntity<String> delete(@PathVariable UUID id,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor) {long v=Versions.parse(match);return writes.execute(actor.id(),"DELETE","/api/v1/admin/menus/"+id,key,v,()->{service.delete(id,v,actor.id());return ResponseEntity.noContent().build();});}
}
