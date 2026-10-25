package cn.zhixiang.platform.knowledge;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import cn.zhixiang.platform.identity.Actor;
import cn.zhixiang.platform.shared.*;
/** 知识历史、关键词检索及索引重建入口。 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeQueryController {
 private final KnowledgeQueryService service;private final IdempotentWrites writes;
 /** 初始化。
 * @param service 查询用例
 * @param writes 幂等协调 */
 public KnowledgeQueryController(KnowledgeQueryService service,IdempotentWrites writes){this.service=service;this.writes=writes;}
 /** 授权历史分页。
 * @param id 内容
 * @param page 页码
 * @param size 页大小
 * @param q 标题搜索
 * @param status 状态
 * @param sort 排序
 * @param actor 身份
 * @return 历史 */
 @GetMapping("/admin/knowledge/{id}/versions") @PreAuthorize("hasAuthority('get_admin_knowledge_id_versions')")
 public PageResult<KnowledgeQueryDtos.Revision> adminVersions(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.versions(id,new ContentDtos.Query(page,size,q,status,sort,null,null,null),actor);}
 /** 授权历史分页。
 * @param id 内容
 * @param page 页码
 * @param size 页大小
 * @param q 标题搜索
 * @param status 状态
 * @param sort 排序
 * @param actor 身份
 * @return 历史 */
 @GetMapping("/student/knowledge/{id}/versions") @PreAuthorize("hasAuthority('get_student_knowledge_id_versions')")
 public PageResult<KnowledgeQueryDtos.Revision> studentVersions(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@AuthenticationPrincipal Actor actor){return service.versions(id,new ContentDtos.Query(page,size,q,status,sort,null,null,null),actor);}
 /** 关键词检索。
 * @param page 页码
 * @param size 页大小
 * @param q 必填搜索词
 * @param status 只允许PUBLISHED
 * @param sort 排序
 * @param categoryId 分类
 * @param tagId 标签
 * @param contentType 类型
 * @param actor 学生
 * @return 带引用结果 */
 @GetMapping("/student/knowledge-search") @PreAuthorize("hasAuthority('get_student_knowledge_search')")
 public PageResult<KnowledgeQueryDtos.Hit> search(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String q,@RequestParam(required=false) String status,@RequestParam(required=false) String sort,@RequestParam(required=false) UUID categoryId,@RequestParam(required=false) UUID tagId,@RequestParam(required=false) String contentType,@AuthenticationPrincipal Actor actor){return service.search(new ContentDtos.Query(page,size,q,status,sort,categoryId,tagId,contentType),actor);}
 /** 重建索引。
 * @param id 内容
 * @param w 原因
 * @param match 版本
 * @param key 幂等键
 * @param actor 管理员
 * @return 真实任务 */
 @PostMapping("/admin/knowledge/{id}/reindex") @PreAuthorize("hasAuthority('post_admin_knowledge_id_reindex')")
 public ResponseEntity<String> reindex(@PathVariable UUID id,@Valid @RequestBody ContentDtos.Reason w,@RequestHeader("If-Match") String match,@RequestHeader("Idempotency-Key") String key,@AuthenticationPrincipal Actor actor){long v=Versions.parse(match);return writes.execute(actor.id(),"POST","/api/v1/admin/knowledge/"+id+"/reindex",key,List.of(v,w),()->{var r=service.reindex(id,v,w.reason(),actor);return ResponseEntity.accepted().eTag(Versions.etag(r.version())).body(r);});}
}
